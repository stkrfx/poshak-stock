package repositories

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stkrfx/stock-backend/internal/models"
)

// SyncHistoryRepository handles incremental delta sync queries and user search history persistence.
type SyncHistoryRepository struct {
	pool *pgxpool.Pool
}

func NewSyncHistoryRepository(pool *pgxpool.Pool) *SyncHistoryRepository {
	return &SyncHistoryRepository{pool: pool}
}

// GetIncrementalSync returns all upserted products, upserted stock rows, and deleted entity IDs
// since the given timestamp. If isAdmin == false, Party is stripped from all returned products.
func (r *SyncHistoryRepository) GetIncrementalSync(
	ctx context.Context,
	since time.Time,
	isAdmin bool,
) (*models.IncrementalSyncResponse, error) {
	syncedAt := time.Now().UTC()

	// 1. Fetch updated/inserted products since `since`
	const prodQ = `
		SELECT
			id,
			source_product_key,
			name,
			brand,
			article,
			color,
			mrp,
			party,
			barcode,
			brandcode,
			updated_at
		FROM products
		WHERE updated_at > $1
		ORDER BY updated_at ASC
	`
	pRows, err := r.pool.Query(ctx, prodQ, since)
	if err != nil {
		return nil, err
	}
	defer pRows.Close()

	products := make([]models.ProductResponse, 0)
	for pRows.Next() {
		var p models.ProductResponse
		var partyVal string
		if err := pRows.Scan(
			&p.ID,
			&p.SourceProductKey,
			&p.Name,
			&p.Brand,
			&p.Article,
			&p.Color,
			&p.MRP,
			&partyVal,
			&p.Barcode,
			&p.Brandcode,
			&p.UpdatedAt,
		); err != nil {
			return nil, err
		}
		if isAdmin {
			p.Party = partyVal
		} else {
			p.Party = ""
		}
		products = append(products, p)
	}
	if err := pRows.Err(); err != nil {
		return nil, err
	}

	// 2. Fetch updated/inserted stock rows since `since`
	const stockQ = `
		SELECT
			id,
			product_id,
			size,
			size_sort_order,
			quantity,
			total_in,
			total_out,
			age_days,
			last_updated
		FROM stock
		WHERE last_updated > $1
		ORDER BY last_updated ASC
	`
	sRows, err := r.pool.Query(ctx, stockQ, since)
	if err != nil {
		return nil, err
	}
	defer sRows.Close()

	stocks := make([]models.StockItemResponse, 0)
	for sRows.Next() {
		var st models.StockItemResponse
		if err := sRows.Scan(
			&st.ID,
			&st.ProductID,
			&st.Size,
			&st.SizeSortOrder,
			&st.Quantity,
			&st.TotalIn,
			&st.TotalOut,
			&st.AgeDays,
			&st.LastUpdated,
		); err != nil {
			return nil, err
		}
		stocks = append(stocks, st)
	}
	if err := sRows.Err(); err != nil {
		return nil, err
	}

	// 3. Fetch deleted product and stock IDs since `since`
	const delQ = `
		SELECT entity_type, entity_id::text
		FROM sync_deleted_records
		WHERE deleted_at > $1
		ORDER BY deleted_at ASC
	`
	dRows, err := r.pool.Query(ctx, delQ, since)
	if err != nil {
		return nil, err
	}
	defer dRows.Close()

	deletedProductIDs := make([]string, 0)
	deletedStockIDs := make([]string, 0)
	for dRows.Next() {
		var entityType, entityID string
		if err := dRows.Scan(&entityType, &entityID); err != nil {
			return nil, err
		}
		switch entityType {
		case "product":
			deletedProductIDs = append(deletedProductIDs, entityID)
		case "stock":
			deletedStockIDs = append(deletedStockIDs, entityID)
		}
	}
	if err := dRows.Err(); err != nil {
		return nil, err
	}

	return &models.IncrementalSyncResponse{
		Upserts: models.IncrementalUpserts{
			Products: products,
			Stocks:   stocks,
		},
		Deletes: models.IncrementalDeletes{
			ProductIDs: deletedProductIDs,
			StockIDs:   deletedStockIDs,
		},
		SyncedAt: syncedAt,
	}, nil
}

// ListSearchHistory returns the latest `limit` search history entries.
// It marks `is_mine: true` when user_id == callerUserID and hides/strips `party` entries for employees.
func (r *SyncHistoryRepository) ListSearchHistory(
	ctx context.Context,
	callerUserID string,
	isAdmin bool,
	limit int,
) ([]models.SearchHistoryItem, error) {
	if limit <= 0 || limit > 100 {
		limit = 40
	}

	// Fetch slightly more rows in case employee filtering skips party-only searches
	fetchLimit := limit * 2
	const q = `
		SELECT id, user_id, query_json, query_text, created_at
		FROM search_history
		ORDER BY created_at DESC
		LIMIT $1
	`
	rows, err := r.pool.Query(ctx, q, fetchLimit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	items := make([]models.SearchHistoryItem, 0, limit)
	for rows.Next() {
		var item models.SearchHistoryItem
		if err := rows.Scan(
			&item.ID,
			&item.UserID,
			&item.QueryJSON,
			&item.QueryText,
			&item.CreatedAt,
		); err != nil {
			return nil, err
		}
		item.IsMine = (item.UserID == callerUserID)

		if !isAdmin {
			var parsed models.StockSearchFilter
			if err := json.Unmarshal(item.QueryJSON, &parsed); err == nil {
				if strings.TrimSpace(parsed.Party) != "" {
					// Strip party from the filter payload for employee visibility
					parsed.Party = ""
					if !parsed.HasPrimaryFilter(false) {
						// Skip history entries that only searched by Party
						continue
					}
					cleanedJSON, err := json.Marshal(parsed)
					if err == nil {
						item.QueryJSON = cleanedJSON
					}
					item.QueryText = sanitizePartyFromQueryText(item.QueryText)
				}
			}
		}

		items = append(items, item)
		if len(items) >= limit {
			break
		}
	}
	return items, rows.Err()
}

func sanitizePartyFromQueryText(text string) string {
	parts := strings.Split(text, "•")
	kept := make([]string, 0, len(parts))
	for _, p := range parts {
		trimmed := strings.TrimSpace(p)
		if strings.HasPrefix(strings.ToLower(trimmed), "party:") {
			continue
		}
		if trimmed != "" {
			kept = append(kept, trimmed)
		}
	}
	return strings.Join(kept, " • ")
}

// CreateSearchHistory inserts a newly executed search into the search_history table.
func (r *SyncHistoryRepository) CreateSearchHistory(
	ctx context.Context,
	userID string,
	queryJSON json.RawMessage,
	queryText string,
) (*models.SearchHistoryItem, error) {
	const q = `
		INSERT INTO search_history (user_id, query_json, query_text)
		VALUES ($1, $2, $3)
		RETURNING id, user_id, query_json, query_text, created_at
	`
	var item models.SearchHistoryItem
	err := r.pool.QueryRow(ctx, q, userID, queryJSON, queryText).Scan(
		&item.ID,
		&item.UserID,
		&item.QueryJSON,
		&item.QueryText,
		&item.CreatedAt,
	)
	if err != nil {
		return nil, err
	}
	item.IsMine = true
	return &item, nil
}
