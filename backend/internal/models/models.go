package models

import (
	"encoding/json"
	"time"
)

// Role constants
const (
	RoleAdmin    = "admin"
	RoleEmployee = "employee"
)

// User status constants (strictly 'active' or 'blocked')
const (
	StatusActive  = "active"
	StatusBlocked = "blocked"
)

// User represents a row in the users table.
type User struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Mobile    string    `json:"mobile"`
	Role      string    `json:"role"`
	Status    string    `json:"status"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

// UserSummary is returned inside login responses.
type UserSummary struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Mobile string `json:"mobile"`
	Role   string `json:"role"`
}

// AdminUserView includes active session count for GET /v1/admin/users.
type AdminUserView struct {
	ID                  string    `json:"id"`
	Name                string    `json:"name"`
	Mobile              string    `json:"mobile"`
	Role                string    `json:"role"`
	Status              string    `json:"status"`
	ActiveSessionCount  int       `json:"active_session_count"`
	CreatedAt           time.Time `json:"created_at"`
	UpdatedAt           time.Time `json:"updated_at"`
}

// Session represents a device session row in the sessions table.
type Session struct {
	ID               string     `json:"id"`
	UserID           string     `json:"user_id"`
	RefreshTokenHash string     `json:"-"`
	DeviceID         string     `json:"device_id"`
	DeviceName       string     `json:"device_name"`
	DeviceType       string     `json:"device_type"`
	IPAddress        string     `json:"ip_address"`
	UserAgent        string     `json:"user_agent"`
	LastUsedAt       time.Time  `json:"last_used_at"`
	CreatedAt        time.Time  `json:"created_at"`
	RevokedAt        *time.Time `json:"revoked_at"`
}

// AppRelease represents a row in the app_releases table.
type AppRelease struct {
	ID                      int       `json:"id"`
	VersionCode             int       `json:"version_code"`
	VersionName             string    `json:"version_name"`
	APKDownloadURL          string    `json:"apk_download_url"`
	APKSha256               string    `json:"apk_sha256"`
	ReleaseNotes            string    `json:"release_notes"`
	IsForceUpdate           bool      `json:"is_force_update"`
	MinSupportedVersionCode int       `json:"min_supported_version_code"`
	CreatedAt               time.Time `json:"created_at"`
}

// VersionCheckResponse is returned by GET /v1/app/version.
type VersionCheckResponse struct {
	LatestVersionCode int    `json:"latest_version_code"`
	LatestVersionName string `json:"latest_version_name"`
	APKDownloadURL    string `json:"apk_download_url"`
	APKSha256         string `json:"apk_sha256"`
	ReleaseNotes      string `json:"release_notes"`
	UpdateAvailable   bool   `json:"update_available"`
	ForceUpdate       bool   `json:"force_update"`
}

// StockItemResponse represents a nested stock variant row inside a product.
type StockItemResponse struct {
	ID            string    `json:"id"`
	ProductID     string    `json:"product_id,omitempty"`
	Size          string    `json:"size"`
	SizeSortOrder int       `json:"size_sort_order"`
	Quantity      int       `json:"quantity"`
	TotalIn       int       `json:"total_in"`
	TotalOut      int       `json:"total_out"`
	AgeDays       *int      `json:"age_days"`
	LastUpdated   time.Time `json:"last_updated"`
}

// ProductResponse represents a product and its nested stock rows.
// Party uses omitempty so setting Party = "" for employee tokens strips it from JSON output.
type ProductResponse struct {
	ID               string              `json:"id"`
	SourceProductKey string              `json:"source_product_key"`
	Name             string              `json:"name"`
	Brand            string              `json:"brand"`
	Article          *string             `json:"article"`
	Color            *string             `json:"color"`
	MRP              int                 `json:"mrp"`
	Party            string              `json:"party,omitempty"`
	Barcode          string              `json:"barcode"`
	Brandcode        string              `json:"brandcode"`
	UpdatedAt        time.Time           `json:"updated_at"`
	Stocks           []StockItemResponse `json:"stocks,omitempty"`
}

// StockSearchFilter encapsulates exact-match filters for stock search and offline downloads.
type StockSearchFilter struct {
	Article   string `json:"article,omitempty"`
	Brand     string `json:"brand,omitempty"`
	Barcode   string `json:"barcode,omitempty"`
	Brandcode string `json:"brandcode,omitempty"`
	Name      string `json:"name,omitempty"`
	Party     string `json:"party,omitempty"`
	Color     string `json:"color,omitempty"`
	Size      string `json:"size,omitempty"`
}

// HasPrimaryFilter checks if at least one primary filter is non-empty.
// For employees, Party is never counted as a valid primary filter.
func (f StockSearchFilter) HasPrimaryFilter(isAdmin bool) bool {
	if f.Article != "" || f.Brand != "" || f.Barcode != "" || f.Brandcode != "" || f.Name != "" {
		return true
	}
	if isAdmin && f.Party != "" {
		return true
	}
	return false
}

// StockSearchResponse is returned by GET /v1/stock/search and POST /v1/sync/offline-downloads.
type StockSearchResponse struct {
	Products []ProductResponse `json:"products"`
	SyncedAt time.Time         `json:"synced_at"`
}

// IncrementalSyncResponse is returned by GET /v1/sync/stock?since=...
type IncrementalSyncResponse struct {
	Upserts  IncrementalUpserts `json:"upserts"`
	Deletes  IncrementalDeletes `json:"deletes"`
	SyncedAt time.Time          `json:"synced_at"`
}

type IncrementalUpserts struct {
	Products []ProductResponse   `json:"products"`
	Stocks   []StockItemResponse `json:"stocks"`
}

type IncrementalDeletes struct {
	ProductIDs []string `json:"product_ids"`
	StockIDs   []string `json:"stock_ids"`
}

// SearchHistoryItem represents a saved search entry returned by GET /v1/search-history.
type SearchHistoryItem struct {
	ID        string          `json:"id"`
	UserID    string          `json:"user_id"`
	IsMine    bool            `json:"is_mine"`
	QueryJSON json.RawMessage `json:"query_json"`
	QueryText string          `json:"query_text"`
	CreatedAt time.Time       `json:"created_at"`
}