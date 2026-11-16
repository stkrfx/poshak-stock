package repositories

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stkrfx/stock-backend/internal/models"
)

// StockRepository handles database queries for autocomplete lookup tables,
// confidential stock search, and full/filtered offline stock downloads.
type StockRepository struct {
	pool *pgxpool.Pool
}

func NewStockRepository(pool *pgxpool.Pool) *StockRepository {
	return &StockRepository{pool: pool}
}

// Autocomplete queries the dedicated lookup_<field> table (or context-scoped distinct values
// for color and size when primary context filters are provided) using prefix matching.
func (r *StockRepository) Autocomplete(
	ctx context.Context,
	field string,
	prefix string,
	isAdmin bool,
	ctxFilter models.StockSearchFilter,
) ([]string, error) {
	normalizedField := strings.ToLower(strings.TrimSpace(field))
	normalizedPrefix := strings.ToLower(strings.TrimSpace(prefix))
	if normalizedPrefix == "" {
		return []string{}, nil
	}

	// Enforce role security: employee can never query party autocomplete.
	if normalizedField == "party" && !isAdmin {
		return nil, fmt.Errorf("forbidden_party_field")
	}

	// If querying color or size with active primary context filters, scope suggestions
	// to matching products/stock rows first so suggestions are relevant to the current selection.
	if (normalizedField == "color" || normalizedField == "size") && ctxFilter.HasPrimaryFilter(isAdmin) {
		return r.scopedColorOrSizeAutocomplete(ctx, normalizedField, normalizedPrefix, isAdmin, ctxFilter)
	}

	var query string
	switch normalizedField {
	case "article":
		query = `
			SELECT display_value
			FROM lookup_articles
			WHERE normalized_value LIKE $1 || '%'
			ORDER BY display_value ASC
			LIMIT 50
		`
	case "brand":
		query = `
			SELECT display_value
			FROM lookup_brands
			WHERE normalized_value LIKE $1 || '%'
			ORDER BY display_value ASC
			LIMIT 50
		`
	case "name":
		query = `
			SELECT display_value
			FROM lookup_names
			WHERE normalized_value LIKE $1 || '%'
			ORDER BY display_value ASC
			LIMIT 50
		`
	case "party":
		query = `
			SELECT display_value
			FROM lookup_parties
			WHERE normalized_value LIKE $1 || '%'
			ORDER BY display_value ASC
			LIMIT 50
		`
	case "color":
		query = `
			SELECT display_value
			FROM lookup_colors
			WHERE normalized_value LIKE $1 || '%'
			ORDER BY display_value ASC
			LIMIT 50
		`
	case "size":
		query = `
			SELECT display_value
			FROM lookup_sizes
			WHERE normalized_value LIKE $1 || '%'
			ORDER BY size_sort_order ASC, display_value ASC
			LIMIT 50
		`
	default:
		return nil, fmt.Errorf("invalid autocomplete field: %s", field)
	}

	rows, err := r.pool.Query(ctx, query, normalizedPrefix)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	suggestions := make([]string, 0, 16)
	for rows.Next() {
		var val string
		if err := rows.Scan(&val); err != nil {
			return nil, err
		}
		suggestions = append(suggestions, val)
	}
	return suggestions, rows.Err()
}

func (r *StockRepository) scopedColorOrSizeAutocomplete(
	ctx context.Context,
	field string,
	normalizedPrefix string,
	isAdmin bool,
	f models.StockSearchFilter,
) ([]string, error) {
	var (
		conds []string
		args  []any
	)
	addArg := func(val any) string {
		args = append(args, val)
		return fmt.Sprintf("$%d", len(args))
	}

	if v := strings.TrimSpace(f.Article); v != "" {
		conds = append(conds, fmt.Sprintf("LOWER(p.article) = LOWER(%s)", addArg(v)))
	}
	if v := strings.TrimSpace(f.Brand); v != "" {
		conds = append(conds, fmt.Sprintf("LOWER(p.brand) = LOWER(%s)", addArg(v)))
	}
	if v := strings.TrimSpace(f.Name); v != "" {
		conds = append(conds, fmt.Sprintf("LOWER(p.name) = LOWER(%s)", addArg(v)))
	}
	if isAdmin {
		if v := strings.TrimSpace(f.Party); v != "" {
			conds = append(conds, fmt.Sprintf("LOWER(p.party) = LOWER(%s)", addArg(v)))
		}
	}
	code := strings.TrimSpace(f.Barcode)
	if code == "" {
		code = strings.TrimSpace(f.Brandcode)
	}
	if code != "" {
		ph := addArg(code)
		conds = append(conds, fmt.Sprintf("(p.barcode = %s OR p.brandcode = %s)", ph, ph))
	}

	prefixPH := addArg(normalizedPrefix)
	whereClause := ""
	if len(conds) > 0 {
		whereClause = " AND " + strings.Join(conds, " AND ")
	}

	var q string
	if field == "color" {
		q = fmt.Sprintf(`
			SELECT DISTINCT p.color
			FROM products p
			WHERE p.color IS NOT NULL
			  AND p.color <> ''
			  AND LOWER(p.color) LIKE %s || '%%'
			  %s
			ORDER BY p.color ASC
			LIMIT 50
		`, prefixPH, whereClause)
	} else {
		q = fmt.Sprintf(`
			SELECT s.size
			FROM stock s
			JOIN products p ON p.id = s.product_id
			WHERE s.size <> ''
			  AND LOWER(s.size) LIKE %s || '%%'
			  %s
			GROUP BY s.size
			ORDER BY MIN(s.size_sort_order) ASC, s.size ASC
			LIMIT 50
		`, prefixPH, whereClause)
	}

	rows, err := r.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	suggestions := make([]string, 0, 16)
	for rows.Next() {
		var val string
		if err := rows.Scan(&val); err != nil {
			return nil, err
		}
		suggestions = append(suggestions, val)
	}
	return suggestions, rows.Err()
}

// SearchStock executes an exact case-insensitive match for all provided filters without pagination or backend sorting.
// If isAdmin == false, party filters are ignored and Party is stripped from all returned ProductResponse items.
func (r *StockRepository) SearchStock(
	ctx context.Context,
	filter models.StockSearchFilter,
	isAdmin bool,
) ([]models.ProductResponse, time.Time, error) {
	var (
		conds []string
		args  []any
	)
	addArg := func(val any) string {
		args = append(args, val)
		return fmt.Sprintf("$%d", len(args))
	}

	if v := strings.TrimSpace(filter.Article); v != "" {
		conds = append(conds, fmt.Sprintf("LOWER(p.article) = LOWER(%s)", addArg(v)))
	}
	if v := strings.TrimSpace(filter.Brand); v != "" {
		conds = append(conds, fmt.Sprintf("LOWER(p.brand) = LOWER(%s)", addArg(v)))
	}
	if v := strings.TrimSpace(filter.Name); v != "" {
		conds = append(conds, fmt.Sprintf("LOWER(p.name) = LOWER(%s)", addArg(v)))
	}
	if isAdmin {
		if v := strings.TrimSpace(filter.Party); v != "" {
			conds = append(conds, fmt.Sprintf("LOWER(p.party) = LOWER(%s)", addArg(v)))
		}
	}
	if v := strings.TrimSpace(filter.Color); v != "" {
		conds = append(conds, fmt.Sprintf("LOWER(p.color) = LOWER(%s)", addArg(v)))
	}

	// Barcode / Brandcode exact match against either column
	if v := strings.TrimSpace(filter.Barcode); v != "" {
		ph := addArg(v)
		conds = append(conds, fmt.Sprintf("(p.barcode = %s OR p.brandcode = %s)", ph, ph))
	}
	if v := strings.TrimSpace(filter.Brandcode); v != "" && !strings.EqualFold(strings.TrimSpace(filter.Brandcode), strings.TrimSpace(filter.Barcode)) {
		ph := addArg(v)
		conds = append(conds, fmt.Sprintf("(p.barcode = %s OR p.brandcode = %s)", ph, ph))
	}

	sizeFilter := strings.TrimSpace(filter.Size)
	if sizeFilter != "" {
		sizePH := addArg(sizeFilter)
		conds = append(conds, fmt.Sprintf(`EXISTS (
			SELECT 1 FROM stock sx
			WHERE sx.product_id = p.id AND LOWER(sx.size) = LOWER(%s)
		)`, sizePH))
	}

	whereSQL := ""
	if len(conds) > 0 {
		whereSQL = "WHERE " + strings.Join(conds, " AND ")
	}

	return r.fetchProductsWithStocks(ctx, whereSQL, args, sizeFilter, isAdmin)
}

// GetOfflineDownloadData retrieves either the entire inventory ("all") or a filtered scope ("filtered").
func (r *StockRepository) GetOfflineDownloadData(
	ctx context.Context,
	scopeType string,
	filter models.StockSearchFilter,
	isAdmin bool,
) ([]models.ProductResponse, time.Time, error) {
	if strings.EqualFold(strings.TrimSpace(scopeType), "all") {
		return r.fetchProductsWithStocks(ctx, "", nil, "", isAdmin)
	}
	return r.SearchStock(ctx, filter, isAdmin)
}

func (r *StockRepository) fetchProductsWithStocks(
	ctx context.Context,
	whereSQL string,
	args []any,
	sizeFilter string,
	isAdmin bool,
) ([]models.ProductResponse, time.Time, error) {
	syncedAt := time.Now().UTC()

	productQuery := fmt.Sprintf(`
		SELECT
			p.id,
			p.source_product_key,
			p.name,
			p.brand,
			p.article,
			p.color,
			p.mrp,
			p.party,
			p.barcode,
			p.brandcode,
			p.updated_at
		FROM products p
		%s
	`, whereSQL)

	rows, err := r.pool.Query(ctx, productQuery, args...)
	if err != nil {
		return nil, syncedAt, err
	}
	defer rows.Close()

	products := make([]models.ProductResponse, 0)
	productIndexByID := make(map[string]int)
	productIDs := make([]string, 0)

	for rows.Next() {
		var p models.ProductResponse
		var partyVal string
		if err := rows.Scan(
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
			return nil, syncedAt, err
		}
		if isAdmin {
			p.Party = partyVal
		} else {
			p.Party = "" // Strictly stripped for employee role
		}
		p.Stocks = []models.StockItemResponse{}
		productIndexByID[p.ID] = len(products)
		productIDs = append(productIDs, p.ID)
		products = append(products, p)
	}
	if err := rows.Err(); err != nil {
		return nil, syncedAt, err
	}

	if len(products) == 0 {
		return products, syncedAt, nil
	}

	// Fetch all stock rows for the matched products in a single query.
	var (
		stockQuery string
		stockArgs  []any
	)
	if sizeFilter != "" {
		stockQuery = `
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
			WHERE product_id = ANY($1)
			  AND LOWER(size) = LOWER($2)
			ORDER BY size_sort_order ASC, size ASC
		`
		stockArgs = []any{productIDs, sizeFilter}
	} else {
		stockQuery = `
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
			WHERE product_id = ANY($1)
			ORDER BY size_sort_order ASC, size ASC
		`
		stockArgs = []any{productIDs}
	}

	sRows, err := r.pool.Query(ctx, stockQuery, stockArgs...)
	if err != nil {
		return nil, syncedAt, err
	}
	defer sRows.Close()

	for sRows.Next() {
		var (
			st        models.StockItemResponse
			productID string
		)
		if err := sRows.Scan(
			&st.ID,
			&productID,
			&st.Size,
			&st.SizeSortOrder,
			&st.Quantity,
			&st.TotalIn,
			&st.TotalOut,
			&st.AgeDays,
			&st.LastUpdated,
		); err != nil {
			return nil, syncedAt, err
		}
		if idx, exists := productIndexByID[productID]; exists {
			products[idx].Stocks = append(products[idx].Stocks, st)
		}
	}
	if err := sRows.Err(); err != nil {
		return nil, syncedAt, err
	}

	return products, syncedAt, nil
}
