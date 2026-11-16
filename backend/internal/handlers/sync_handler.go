package handlers

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/stkrfx/stock-backend/internal/middleware"
	"github.com/stkrfx/stock-backend/internal/models"
	"github.com/stkrfx/stock-backend/internal/repositories"
	"github.com/stkrfx/stock-backend/internal/utils"
)

// SyncHandler implements offline downloads, incremental delta sync, search history, and health endpoints.
type SyncHandler struct {
	stockRepo *repositories.StockRepository
	syncRepo  *repositories.SyncHistoryRepository
}

func NewSyncHandler(
	stockRepo *repositories.StockRepository,
	syncRepo *repositories.SyncHistoryRepository,
) *SyncHandler {
	return &SyncHandler{
		stockRepo: stockRepo,
		syncRepo:  syncRepo,
	}
}

type offlineDownloadRequest struct {
	ScopeType string                  `json:"scope_type"`
	Filters   models.StockSearchFilter `json:"filters"`
}

// OfflineDownloads handles POST /v1/sync/offline-downloads.
// Supports scope_type: "all" (full inventory download) or "filtered" (matching specific filters).
func (h *SyncHandler) OfflineDownloads(w http.ResponseWriter, r *http.Request) {
	var req offlineDownloadRequest
	if err := utils.DecodeJSON(r, &req); err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid JSON request body")
		return
	}

	scopeType := strings.ToLower(strings.TrimSpace(req.ScopeType))
	if scopeType != "all" && scopeType != "filtered" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "scope_type must be 'all' or 'filtered'")
		return
	}

	isAdmin := middleware.GetUserRole(r.Context()) == models.RoleAdmin
	if !isAdmin {
		if strings.TrimSpace(req.Filters.Party) != "" {
			utils.WriteError(w, http.StatusForbidden, utils.ErrCodeForbidden, "Party filter is restricted to admin users")
			return
		}
		req.Filters.Party = ""
	}

	if scopeType == "filtered" && !req.Filters.HasPrimaryFilter(isAdmin) {
		utils.WriteError(
			w,
			http.StatusBadRequest,
			utils.ErrCodeBadRequest,
			"Filtered offline downloads require at least one primary filter (article, brand, barcode, brandcode, name, or party)",
		)
		return
	}

	products, syncedAt, err := h.stockRepo.GetOfflineDownloadData(r.Context(), scopeType, req.Filters, isAdmin)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to fetch offline download dataset")
		return
	}

	utils.WriteJSON(w, http.StatusOK, models.StockSearchResponse{
		Products: products,
		SyncedAt: syncedAt,
	})
}

// IncrementalSync handles GET /v1/sync/stock?since=<RFC3339_timestamp>.
func (h *SyncHandler) IncrementalSync(w http.ResponseWriter, r *http.Request) {
	sinceRaw := strings.TrimSpace(r.URL.Query().Get("since"))
	sinceTime := time.Unix(0, 0).UTC()

	if sinceRaw != "" {
		parsed, err := time.Parse(time.RFC3339Nano, sinceRaw)
		if err != nil {
			parsed, err = time.Parse(time.RFC3339, sinceRaw)
			if err != nil {
				utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "since parameter must be a valid RFC3339 timestamp")
				return
			}
		}
		sinceTime = parsed.UTC()
	}

	isAdmin := middleware.GetUserRole(r.Context()) == models.RoleAdmin
	resp, err := h.syncRepo.GetIncrementalSync(r.Context(), sinceTime, isAdmin)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to fetch incremental sync updates")
		return
	}

	utils.WriteJSON(w, http.StatusOK, resp)
}

// GetSearchHistory handles GET /v1/search-history?limit=40.
func (h *SyncHandler) GetSearchHistory(w http.ResponseWriter, r *http.Request) {
	limit := 40
	if rawLimit := strings.TrimSpace(r.URL.Query().Get("limit")); rawLimit != "" {
		if parsed, err := strconv.Atoi(rawLimit); err == nil && parsed > 0 {
			limit = parsed
		}
	}

	userID := middleware.GetUserID(r.Context())
	isAdmin := middleware.GetUserRole(r.Context()) == models.RoleAdmin

	items, err := h.syncRepo.ListSearchHistory(r.Context(), userID, isAdmin, limit)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to fetch search history")
		return
	}

	utils.WriteJSON(w, http.StatusOK, items)
}

type createSearchHistoryRequest struct {
	QueryJSON json.RawMessage `json:"query_json"`
	QueryText string          `json:"query_text"`
}

// CreateSearchHistory handles POST /v1/search-history.
func (h *SyncHandler) CreateSearchHistory(w http.ResponseWriter, r *http.Request) {
	var req createSearchHistoryRequest
	if err := utils.DecodeJSON(r, &req); err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid JSON request body")
		return
	}

	req.QueryText = strings.TrimSpace(req.QueryText)
	if len(req.QueryJSON) == 0 || req.QueryText == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "query_json and query_text are required")
		return
	}

	userID := middleware.GetUserID(r.Context())
	item, err := h.syncRepo.CreateSearchHistory(r.Context(), userID, req.QueryJSON, req.QueryText)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to record search history")
		return
	}

	utils.WriteJSON(w, http.StatusCreated, item)
}

// Health handles GET /v1/health.
func (h *SyncHandler) Health(w http.ResponseWriter, r *http.Request) {
	utils.WriteJSON(w, http.StatusOK, map[string]string{
		"status": "ok",
	})
}
