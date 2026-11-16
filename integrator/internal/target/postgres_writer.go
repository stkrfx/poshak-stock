package target

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stkrfx/stock-integrator/internal/models"
)

const (
	MetaKeyLastCursorTS     = "habron_last_cursor_ts"
	MetaKeyLastSuccessAt    = "habron_last_successful_sync_at"
	MetaKeySyncStatus       = "habron_sync_status"
	MetaKeyLastErrorMessage = "habron_last_error_message"
)

// PostgresWriter manages transactional batch upserts into Supabase PostgreSQL
// (`products`, `stock`, `lookup_*`, `sync_deleted_records`, and `sync_metadata`).
type PostgresWriter struct {
	pool      *pgxpool.Pool
	batchSize int
}

// NewPostgresWriter initializes a TLS-capable pgxpool.Pool to Supabase PostgreSQL.
func NewPostgresWriter(ctx context.Context, databaseURL string, batchSize int) (*PostgresWriter, error) {
	poolCfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("failed to parse SUPABASE_DATABASE_URL: %w", err)
	}

	poolCfg.MaxConns = 10
	poolCfg.MinConns = 1
	poolCfg.MaxConnLifetime = 30 * time.Minute
	poolCfg.MaxConnIdleTime = 5 * time.Minute

	pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
	if err != nil {
		return nil, fmt.Errorf("failed to create PostgreSQL pool: %w", err)
	}

	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("failed to ping Supabase PostgreSQL: %w", err)
	}

	return &PostgresWriter{
		pool:      pool,
		batchSize: batchSize,
	}, nil
}

// Close closes the PostgreSQL connection pool.
func (w *PostgresWriter) Close() {
	if w.pool != nil {
		w.pool.Close()
	}
}

// GetLastSyncedCursor reads the last synced Habron timestamp from sync_metadata.
func (w *PostgresWriter) GetLastSyncedCursor(ctx context.Context) (time.Time, error) {
	const q = `SELECT value FROM sync_metadata WHERE key = $1`
	var val string
	err := w.pool.QueryRow(ctx, q, MetaKeyLastCursorTS).Scan(&val)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, nil
	}
	if err != nil {
		return time.Time{}, err
	}
	parsed, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(val))
	if err != nil {
		return time.Time{}, nil
	}
	return parsed, nil
}

// RecordSyncFailure records an unhealthy sync status and error message in sync_metadata.
func (w *PostgresWriter) RecordSyncFailure(ctx context.Context, errMsg string) {
	const q = `
		INSERT INTO sync_metadata (key, value, updated_at)
		VALUES ($1, $2, NOW())
		ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
	`
	_, _ = w.pool.Exec(ctx, q, MetaKeySyncStatus, "degraded")
	_, _ = w.pool.Exec(ctx, q, MetaKeyLastErrorMessage, errMsg)
}

// ApplyExtractedProducts upserts products, stock variants, and lookup_* tables in batched transactions.
// When isFullReconciliation is true, it also detects products/stock rows deleted in Habron,
// logs their UUIDs into sync_deleted_records, deletes them from products/stock, and prunes stale lookup_* rows.
func (w *PostgresWriter) ApplyExtractedProducts(
	ctx context.Context,
	records []models.HabronProductRecord,
	newCursorTS time.Time,
	isFullReconciliation bool,
) (*models.SyncStats, error) {
	startTime := time.Now()
	stats := &models.SyncStats{
		IsFullReconciliation: isFullReconciliation,
		MaxSourceUpdatedAt:   newCursorTS,
	}

	// Process product & stock upserts + lookup_* upserts in batches
	for start := 0; start < len(records); start += w.batchSize {
		end := start + w.batchSize
		if end > len(records) {
			end = len(records)
		}
		batch := records[start:end]

		pCount, sCount, lCount, err := w.upsertBatchTransactionally(ctx, batch, isFullReconciliation)
		if err != nil {
			return nil, err
		}
		stats.ProductsUpserted += pCount
		stats.StocksUpserted += sCount
		stats.LookupsUpserted += lCount
	}

	// On Full Snapshot Reconciliation, detect and remove any product rows that no longer exist in Habron
	// and log their UUIDs into sync_deleted_records for incremental Android client sync.
	if isFullReconciliation && len(records) > 0 {
		delProds, delStocks, err := w.reconcileDeletedProductsAndLookups(ctx, records)
		if err != nil {
			return nil, err
		}
		stats.ProductsDeleted += delProds
		stats.StocksDeleted += delStocks
	}

	// Update sync_metadata health & cursor
	if err := w.updateSyncMetadataHealthy(ctx, newCursorTS); err != nil {
		return nil, err
	}

	stats.Duration = time.Since(startTime)
	return stats, nil
}

func (w *PostgresWriter) upsertBatchTransactionally(
	ctx context.Context,
	batch []models.HabronProductRecord,
	reconcileRemovedSizesPerProduct bool,
) (productsUpserted int, stocksUpserted int, lookupsUpserted int, err error) {
	tx, err := w.pool.Begin(ctx)
	if err != nil {
		return 0, 0, 0, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	const upsertProductSQL = `
		INSERT INTO products (
			source_product_key,
			name,
			brand,
			article,
			color,
			mrp,
			party,
			barcode,
			brandcode,
			source_updated_at
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
		ON CONFLICT (source_product_key) DO UPDATE SET
			name = EXCLUDED.name,
			brand = EXCLUDED.brand,
			article = EXCLUDED.article,
			color = EXCLUDED.color,
			mrp = EXCLUDED.mrp,
			party = EXCLUDED.party,
			barcode = EXCLUDED.barcode,
			brandcode = EXCLUDED.brandcode,
			source_updated_at = EXCLUDED.source_updated_at,
			updated_at = NOW()
		RETURNING id
	`

	const upsertStockSQL = `
		INSERT INTO stock (
			product_id,
			size,
			size_sort_order,
			quantity,
			total_in,
			total_out,
			age_days,
			source_updated_at
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (product_id, size) DO UPDATE SET
			size_sort_order = EXCLUDED.size_sort_order,
			quantity = EXCLUDED.quantity,
			total_in = EXCLUDED.total_in,
			total_out = EXCLUDED.total_out,
			age_days = EXCLUDED.age_days,
			source_updated_at = EXCLUDED.source_updated_at,
			last_updated = NOW()
	`

	articles := make(map[string]string)
	brands := make(map[string]string)
	names := make(map[string]string)
	parties := make(map[string]string)
	colors := make(map[string]string)
	sizes := make(map[string]models.LookupValue)

	for _, prod := range batch {
		var productID string
		if err := tx.QueryRow(
			ctx,
			upsertProductSQL,
			prod.SourceProductKey,
			prod.Name,
			prod.Brand,
			prod.Article,
			prod.Color,
			prod.MRP,
			prod.Party,
			prod.Barcode,
			prod.Brandcode,
			prod.SourceUpdatedAt,
		).Scan(&productID); err != nil {
			return 0, 0, 0, fmt.Errorf("failed to upsert product (%s): %w", prod.SourceProductKey, err)
		}
		productsUpserted++

		if prod.Article != nil && strings.TrimSpace(*prod.Article) != "" {
			v := strings.TrimSpace(*prod.Article)
			articles[strings.ToLower(v)] = v
		}
		if v := strings.TrimSpace(prod.Brand); v != "" {
			brands[strings.ToLower(v)] = v
		}
		if v := strings.TrimSpace(prod.Name); v != "" {
			names[strings.ToLower(v)] = v
		}
		if v := strings.TrimSpace(prod.Party); v != "" {
			parties[strings.ToLower(v)] = v
		}
		if prod.Color != nil && strings.TrimSpace(*prod.Color) != "" {
			v := strings.TrimSpace(*prod.Color)
			colors[strings.ToLower(v)] = v
		}

		activeSizes := make([]string, 0, len(prod.Stocks))
		for _, st := range prod.Stocks {
			if _, err := tx.Exec(
				ctx,
				upsertStockSQL,
				productID,
				st.Size,
				st.SizeSortOrder,
				st.Quantity,
				st.TotalIn,
				st.TotalOut,
				st.AgeDays,
				st.SourceUpdatedAt,
			); err != nil {
				return 0, 0, 0, fmt.Errorf("failed to upsert stock (%s / %s): %w", prod.SourceProductKey, st.Size, err)
			}
			stocksUpserted++
			activeSizes = append(activeSizes, st.Size)

			normSize := strings.ToLower(strings.TrimSpace(st.Size))
			if normSize != "" {
				existing, exists := sizes[normSize]
				if !exists || st.SizeSortOrder < existing.SizeSortOrder {
					sizes[normSize] = models.LookupValue{
						NormalizedValue: normSize,
						DisplayValue:    strings.TrimSpace(st.Size),
						SizeSortOrder:   st.SizeSortOrder,
					}
				}
			}
		}

		// If reconciling a product's complete size set, log & delete any stale sizes for this product
		if reconcileRemovedSizesPerProduct && len(activeSizes) > 0 {
			const logDeletedSizesSQL = `
				WITH deleted_rows AS (
					DELETE FROM stock
					WHERE product_id = $1
					  AND NOT (size = ANY($2))
					RETURNING id
				)
				INSERT INTO sync_deleted_records (entity_type, entity_id)
				SELECT 'stock', id FROM deleted_rows
			`
			_, _ = tx.Exec(ctx, logDeletedSizesSQL, productID, activeSizes)
		}
	}

	// Upsert distinct non-empty lookup values into lookup_articles, lookup_brands, lookup_names,
	// lookup_parties, lookup_colors, and lookup_sizes
	lCount, err := upsertLookupMaps(ctx, tx, articles, brands, names, parties, colors, sizes)
	if err != nil {
		return 0, 0, 0, err
	}
	lookupsUpserted += lCount

	if err := tx.Commit(ctx); err != nil {
		return 0, 0, 0, err
	}
	return productsUpserted, stocksUpserted, lookupsUpserted, nil
}

func upsertLookupMaps(
	ctx context.Context,
	tx pgx.Tx,
	articles map[string]string,
	brands map[string]string,
	names map[string]string,
	parties map[string]string,
	colors map[string]string,
	sizes map[string]models.LookupValue,
) (int, error) {
	total := 0

	upsertSimple := func(table string, values map[string]string) error {
		if len(values) == 0 {
			return nil
		}
		q := fmt.Sprintf(`
			INSERT INTO %s (normalized_value, display_value, updated_at)
			VALUES ($1, $2, NOW())
			ON CONFLICT (normalized_value) DO UPDATE SET
				display_value = EXCLUDED.display_value,
				updated_at = NOW()
		`, table)
		for norm, disp := range values {
			if _, err := tx.Exec(ctx, q, norm, disp); err != nil {
				return fmt.Errorf("failed to upsert %s (%s): %w", table, disp, err)
			}
			total++
		}
		return nil
	}

	if err := upsertSimple("lookup_articles", articles); err != nil {
		return total, err
	}
	if err := upsertSimple("lookup_brands", brands); err != nil {
		return total, err
	}
	if err := upsertSimple("lookup_names", names); err != nil {
		return total, err
	}
	if err := upsertSimple("lookup_parties", parties); err != nil {
		return total, err
	}
	if err := upsertSimple("lookup_colors", colors); err != nil {
		return total, err
	}

	if len(sizes) > 0 {
		const sizeQ = `
			INSERT INTO lookup_sizes (normalized_value, display_value, size_sort_order, updated_at)
			VALUES ($1, $2, $3, NOW())
			ON CONFLICT (normalized_value) DO UPDATE SET
				display_value = EXCLUDED.display_value,
				size_sort_order = LEAST(lookup_sizes.size_sort_order, EXCLUDED.size_sort_order),
				updated_at = NOW()
		`
		for _, sv := range sizes {
			if _, err := tx.Exec(ctx, sizeQ, sv.NormalizedValue, sv.DisplayValue, sv.SizeSortOrder); err != nil {
				return total, fmt.Errorf("failed to upsert lookup_sizes (%s): %w", sv.DisplayValue, err)
			}
			total++
		}
	}

	return total, nil
}

func (w *PostgresWriter) reconcileDeletedProductsAndLookups(
	ctx context.Context,
	snapshotRecords []models.HabronProductRecord,
) (deletedProducts int, deletedStocks int, err error) {
	tx, err := w.pool.Begin(ctx)
	if err != nil {
		return 0, 0, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	activeSourceKeys := make([]string, 0, len(snapshotRecords))
	for _, r := range snapshotRecords {
		activeSourceKeys = append(activeSourceKeys, r.SourceProductKey)
	}

	// 1. Log any stock rows belonging to removed products into sync_deleted_records before cascade delete
	const logDeletedStockForProductsSQL = `
		INSERT INTO sync_deleted_records (entity_type, entity_id)
		SELECT 'stock', s.id
		FROM stock s
		JOIN products p ON p.id = s.product_id
		WHERE NOT (p.source_product_key = ANY($1))
	`
	tagStocks, err := tx.Exec(ctx, logDeletedStockForProductsSQL, activeSourceKeys)
	if err != nil {
		return 0, 0, fmt.Errorf("failed to log deleted stocks during reconciliation: %w", err)
	}
	deletedStocks = int(tagStocks.RowsAffected())

	// 2. Delete removed products and log their UUIDs into sync_deleted_records
	const deleteRemovedProductsSQL = `
		WITH deleted_prods AS (
			DELETE FROM products
			WHERE NOT (source_product_key = ANY($1))
			RETURNING id
		)
		INSERT INTO sync_deleted_records (entity_type, entity_id)
		SELECT 'product', id FROM deleted_prods
	`
	tagProds, err := tx.Exec(ctx, deleteRemovedProductsSQL, activeSourceKeys)
	if err != nil {
		return 0, 0, fmt.Errorf("failed to delete removed products during reconciliation: %w", err)
	}
	deletedProducts = int(tagProds.RowsAffected())

	// 3. Prune any stale lookup_* values that no longer exist in products / stock
	pruneStatements := []string{
		`DELETE FROM lookup_articles la WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.article IS NOT NULL AND LOWER(p.article) = la.normalized_value)`,
		`DELETE FROM lookup_brands lb WHERE NOT EXISTS (SELECT 1 FROM products p WHERE LOWER(p.brand) = lb.normalized_value)`,
		`DELETE FROM lookup_names ln WHERE NOT EXISTS (SELECT 1 FROM products p WHERE LOWER(p.name) = ln.normalized_value)`,
		`DELETE FROM lookup_parties lp WHERE NOT EXISTS (SELECT 1 FROM products p WHERE LOWER(p.party) = lp.normalized_value)`,
		`DELETE FROM lookup_colors lc WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.color IS NOT NULL AND LOWER(p.color) = lc.normalized_value)`,
		`DELETE FROM lookup_sizes ls WHERE NOT EXISTS (SELECT 1 FROM stock s WHERE LOWER(s.size) = ls.normalized_value)`,
	}
	for _, stmt := range pruneStatements {
		if _, err := tx.Exec(ctx, stmt); err != nil {
			return 0, 0, fmt.Errorf("failed to prune stale lookup table: %w", err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return 0, 0, err
	}
	return deletedProducts, deletedStocks, nil
}

func (w *PostgresWriter) updateSyncMetadataHealthy(ctx context.Context, cursorTS time.Time) error {
	const upsertMetaSQL = `
		INSERT INTO sync_metadata (key, value, updated_at)
		VALUES ($1, $2, NOW())
		ON CONFLICT (key) DO UPDATE SET
			value = EXCLUDED.value,
			updated_at = NOW()
	`
	nowISO := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := w.pool.Exec(ctx, upsertMetaSQL, MetaKeyLastSuccessAt, nowISO); err != nil {
		return err
	}
	if _, err := w.pool.Exec(ctx, upsertMetaSQL, MetaKeySyncStatus, "healthy"); err != nil {
		return err
	}
	if !cursorTS.IsZero() {
		cursorISO := cursorTS.UTC().Format(time.RFC3339Nano)
		if _, err := w.pool.Exec(ctx, upsertMetaSQL, MetaKeyLastCursorTS, cursorISO); err != nil {
			return err
		}
	}
	return nil
}
