package source

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	_ "github.com/microsoft/go-mssqldb"
	"github.com/stkrfx/stock-integrator/internal/models"
)

// MSSQLExtractor performs strictly read-only SELECT queries against Habron SQL Server.
// It NEVER modifies Habron SQL Server, NEVER generates barcodes, and NEVER fabricates fallback values.
type MSSQLExtractor struct {
	db         *sql.DB
	sourceView string
}

// NewMSSQLExtractor opens a read-only connection pool to Habron SQL Server and verifies connectivity.
func NewMSSQLExtractor(ctx context.Context, dsn string, sourceView string) (*MSSQLExtractor, error) {
	db, err := sql.Open("sqlserver", dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to open MSSQL driver: %w", err)
	}

	db.SetMaxOpenConns(5)
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(15 * time.Minute)
	db.SetConnMaxIdleTime(5 * time.Minute)

	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := db.PingContext(pingCtx); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("failed to ping Habron SQL Server: %w", err)
	}

	return &MSSQLExtractor{
		db:         db,
		sourceView: sourceView,
	}, nil
}

// Close closes the underlying Habron SQL Server connection pool.
func (e *MSSQLExtractor) Close() error {
	if e.db != nil {
		return e.db.Close()
	}
	return nil
}

// ExtractProducts pulls ready-to-store product and stock records from Habron SQL Server.
// If since is non-zero, only rows with source_updated_at > since are extracted;
// otherwise a full snapshot is extracted.
func (e *MSSQLExtractor) ExtractProducts(
	ctx context.Context,
	since time.Time,
) ([]models.HabronProductRecord, time.Time, error) {
	var (
		query string
		args  []any
	)

	// Read-only SELECT with NOLOCK so the shop's POS transactions are never blocked by inventory sync.
	if !since.IsZero() {
		query = fmt.Sprintf(`
			SELECT
				CAST(source_product_key AS NVARCHAR(255)) AS source_product_key,
				CAST(name AS NVARCHAR(255)) AS name,
				CAST(brand AS NVARCHAR(255)) AS brand,
				CAST(article AS NVARCHAR(255)) AS article,
				CAST(color AS NVARCHAR(255)) AS color,
				CAST(mrp AS INT) AS mrp,
				CAST(party AS NVARCHAR(255)) AS party,
				CAST(barcode AS NVARCHAR(255)) AS barcode,
				CAST(brandcode AS NVARCHAR(255)) AS brandcode,
				CAST(size AS NVARCHAR(100)) AS size,
				CAST(ISNULL(size_sort_order, 999999) AS INT) AS size_sort_order,
				CAST(ISNULL(quantity, 0) AS INT) AS quantity,
				CAST(ISNULL(total_in, 0) AS INT) AS total_in,
				CAST(ISNULL(total_out, 0) AS INT) AS total_out,
				CAST(age_days AS INT) AS age_days,
				ISNULL(source_updated_at, SYSUTCDATETIME()) AS source_updated_at
			FROM %s WITH (NOLOCK)
			WHERE source_updated_at > @p1
			ORDER BY source_updated_at ASC, source_product_key ASC
		`, e.sourceView)
		args = append(args, since.UTC())
	} else {
		query = fmt.Sprintf(`
			SELECT
				CAST(source_product_key AS NVARCHAR(255)) AS source_product_key,
				CAST(name AS NVARCHAR(255)) AS name,
				CAST(brand AS NVARCHAR(255)) AS brand,
				CAST(article AS NVARCHAR(255)) AS article,
				CAST(color AS NVARCHAR(255)) AS color,
				CAST(mrp AS INT) AS mrp,
				CAST(party AS NVARCHAR(255)) AS party,
				CAST(barcode AS NVARCHAR(255)) AS barcode,
				CAST(brandcode AS NVARCHAR(255)) AS brandcode,
				CAST(size AS NVARCHAR(100)) AS size,
				CAST(ISNULL(size_sort_order, 999999) AS INT) AS size_sort_order,
				CAST(ISNULL(quantity, 0) AS INT) AS quantity,
				CAST(ISNULL(total_in, 0) AS INT) AS total_in,
				CAST(ISNULL(total_out, 0) AS INT) AS total_out,
				CAST(age_days AS INT) AS age_days,
				ISNULL(source_updated_at, SYSUTCDATETIME()) AS source_updated_at
			FROM %s WITH (NOLOCK)
			ORDER BY source_product_key ASC
		`, e.sourceView)
	}

	rows, err := e.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, since, fmt.Errorf("failed to query Habron source (%s): %w", e.sourceView, err)
	}
	defer rows.Close()

	productMap := make(map[string]*models.HabronProductRecord)
	orderedKeys := make([]string, 0, 256)
	maxUpdated := since

	for rows.Next() {
		var (
			raw        models.HabronRawRow
			articleNS  sql.NullString
			colorNS    sql.NullString
			ageDaysNI  sql.NullInt64
		)

		if err := rows.Scan(
			&raw.SourceProductKey,
			&raw.Name,
			&raw.Brand,
			&articleNS,
			&colorNS,
			&raw.MRP,
			&raw.Party,
			&raw.Barcode,
			&raw.Brandcode,
			&raw.Size,
			&raw.SizeSortOrder,
			&raw.Quantity,
			&raw.TotalIn,
			&raw.TotalOut,
			&ageDaysNI,
			&raw.SourceUpdatedAt,
		); err != nil {
			return nil, since, fmt.Errorf("failed to scan Habron row: %w", err)
		}

		raw.SourceProductKey = strings.TrimSpace(raw.SourceProductKey)
		raw.Name = strings.TrimSpace(raw.Name)
		raw.Brand = strings.TrimSpace(raw.Brand)
		raw.Party = strings.TrimSpace(raw.Party)
		raw.Barcode = strings.TrimSpace(raw.Barcode)
		raw.Brandcode = strings.TrimSpace(raw.Brandcode)
		raw.Size = strings.TrimSpace(raw.Size)

		// PostgreSQL schema does NOT allow NULL/empty sizes or missing product keys
		if raw.SourceProductKey == "" || raw.Size == "" {
			continue
		}

		if articleNS.Valid {
			trimmed := strings.TrimSpace(articleNS.String)
			if trimmed != "" {
				raw.Article = &trimmed
			}
		}
		if colorNS.Valid {
			trimmed := strings.TrimSpace(colorNS.String)
			if trimmed != "" {
				raw.Color = &trimmed
			}
		}
		if ageDaysNI.Valid {
			val := int(ageDaysNI.Int64)
			raw.AgeDays = &val
		}

		if raw.SourceUpdatedAt.After(maxUpdated) {
			maxUpdated = raw.SourceUpdatedAt
		}

		prod, exists := productMap[raw.SourceProductKey]
		if !exists {
			prod = &models.HabronProductRecord{
				SourceProductKey: raw.SourceProductKey,
				Name:             raw.Name,
				Brand:            raw.Brand,
				Article:          raw.Article,
				Color:            raw.Color,
				MRP:              raw.MRP,
				Party:            raw.Party,
				Barcode:          raw.Barcode,
				Brandcode:        raw.Brandcode,
				SourceUpdatedAt:  raw.SourceUpdatedAt,
				Stocks:           make([]models.HabronStockVariant, 0, 6),
			}
			productMap[raw.SourceProductKey] = prod
			orderedKeys = append(orderedKeys, raw.SourceProductKey)
		} else if raw.SourceUpdatedAt.After(prod.SourceUpdatedAt) {
			prod.Name = raw.Name
			prod.Brand = raw.Brand
			prod.Article = raw.Article
			prod.Color = raw.Color
			prod.MRP = raw.MRP
			prod.Party = raw.Party
			prod.Barcode = raw.Barcode
			prod.Brandcode = raw.Brandcode
			prod.SourceUpdatedAt = raw.SourceUpdatedAt
		}

		prod.Stocks = append(prod.Stocks, models.HabronStockVariant{
			Size:            raw.Size,
			SizeSortOrder:   raw.SizeSortOrder,
			Quantity:        raw.Quantity,
			TotalIn:         raw.TotalIn,
			TotalOut:        raw.TotalOut,
			AgeDays:         raw.AgeDays,
			SourceUpdatedAt: raw.SourceUpdatedAt,
		})
	}

	if err := rows.Err(); err != nil {
		return nil, since, fmt.Errorf("error iterating Habron rows: %w", err)
	}

	result := make([]models.HabronProductRecord, 0, len(orderedKeys))
	for _, key := range orderedKeys {
		result = append(result, *productMap[key])
	}

	return result, maxUpdated, nil
}
