package handlers

import (
	"net/http"
	"strings"

	"github.com/stkrfx/stock-backend/internal/middleware"
	"github.com/stkrfx/stock-backend/internal/models"
	"github.com/stkrfx/stock-backend/internal/repositories"
	"github.com/stkrfx/stock-backend/internal/utils"
)

// StockHandler implements /v1/autocomplete and /v1/stock/search.
type StockHandler struct {
	stockRepo *repositories.StockRepository
}

func NewStockHandler(stockRepo *repositories.StockRepository) *StockHandler {
	return &StockHandler{stockRepo: stockRepo}
}

// Autocomplete handles GET /v1/autocomplete.
// Query Params:
// - field: article | brand | name | party (admin only) | color | size
// - prefix: non-empty string
// - Optional context filters when querying size or color: article, brand, name, party, barcode, brandcode
func (h *StockHandler) Autocomplete(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	field := strings.ToLower(strings.TrimSpace(q.Get("field")))
	prefix := strings.ToLower(strings.TrimSpace(q.Get("prefix")))

	if field == "" || prefix == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "field and non-empty prefix query parameters are required")
		return
	}

	switch field {
	case "article", "brand", "name", "party", "color", "size":
	default:
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "field must be one of: article, brand, name, party, color, size")
		return
	}

	isAdmin := middleware.GetUserRole(r.Context()) == models.RoleAdmin
	if field == "party" && !isAdmin {
		utils.WriteError(w, http.StatusForbidden, utils.ErrCodeForbidden, "Party autocomplete is restricted to admin users")
		return
	}

	ctxFilter := models.StockSearchFilter{
		Article:   strings.TrimSpace(q.Get("article")),
		Brand:     strings.TrimSpace(q.Get("brand")),
		Barcode:   strings.TrimSpace(q.Get("barcode")),
		Brandcode: strings.TrimSpace(q.Get("brandcode")),
		Name:      strings.TrimSpace(q.Get("name")),
		Party:     strings.TrimSpace(q.Get("party")),
	}
	if !isAdmin {
		ctxFilter.Party = ""
	}

	// Enforce Non-Solo Rule on backend for size/color autocomplete if desired, or scope when context filters exist
	suggestions, err := h.stockRepo.Autocomplete(r.Context(), field, prefix, isAdmin, ctxFilter)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to query autocomplete suggestions")
		return
	}

	utils.WriteJSON(w, http.StatusOK, suggestions)
}

// SearchStock handles GET /v1/stock/search.
// Rules:
// - No pagination (page, limit) and no backend sorting.
// - At least one primary filter (article, brand, barcode, brandcode, name, or party [admin only]) MUST be non-empty.
// - If caller role == 'employee', party filter is rejected/ignored and party is omitted from every returned product.
func (h *StockHandler) SearchStock(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	isAdmin := middleware.GetUserRole(r.Context()) == models.RoleAdmin

	rawParty := strings.TrimSpace(q.Get("party"))
	if !isAdmin && rawParty != "" {
		utils.WriteError(w, http.StatusForbidden, utils.ErrCodeForbidden, "Party filter is restricted to admin users")
		return
	}

	filter := models.StockSearchFilter{
		Article:   strings.TrimSpace(q.Get("article")),
		Brand:     strings.TrimSpace(q.Get("brand")),
		Barcode:   strings.TrimSpace(q.Get("barcode")),
		Brandcode: strings.TrimSpace(q.Get("brandcode")),
		Name:      strings.TrimSpace(q.Get("name")),
		Color:     strings.TrimSpace(q.Get("color")),
		Size:      strings.TrimSpace(q.Get("size")),
	}
	if isAdmin {
		filter.Party = rawParty
	}

	if !filter.HasPrimaryFilter(isAdmin) {
		utils.WriteError(
			w,
			http.StatusBadRequest,
			utils.ErrCodeBadRequest,
			"At least one primary filter (article, brand, barcode, brandcode, name, or party) is required; Size and Color cannot be searched alone",
		)
		return
	}

	products, syncedAt, err := h.stockRepo.SearchStock(r.Context(), filter, isAdmin)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to execute stock search")
		return
	}

	utils.WriteJSON(w, http.StatusOK, models.StockSearchResponse{
		Products: products,
		SyncedAt: syncedAt,
	})
}
