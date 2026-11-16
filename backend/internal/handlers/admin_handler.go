package handlers

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/stkrfx/stock-backend/internal/config"
	"github.com/stkrfx/stock-backend/internal/middleware"
	"github.com/stkrfx/stock-backend/internal/models"
	"github.com/stkrfx/stock-backend/internal/repositories"
	"github.com/stkrfx/stock-backend/internal/utils"
)

// AdminHandler implements all Hoppscotch administrative routes under /v1/admin/*.
type AdminHandler struct {
	cfg            *config.Config
	userRepo       *repositories.UserSessionRepository
	releaseRepo    *repositories.ReleaseRepository
	authMiddleware *middleware.AuthMiddleware
}

func NewAdminHandler(
	cfg *config.Config,
	userRepo *repositories.UserSessionRepository,
	releaseRepo *repositories.ReleaseRepository,
	authMiddleware *middleware.AuthMiddleware,
) *AdminHandler {
	return &AdminHandler{
		cfg:            cfg,
		userRepo:       userRepo,
		releaseRepo:    releaseRepo,
		authMiddleware: authMiddleware,
	}
}

type createUserRequest struct {
	Name   string `json:"name"`
	Mobile string `json:"mobile"`
	Role   string `json:"role"`
}

// CreateUser handles POST /v1/admin/users.
func (h *AdminHandler) CreateUser(w http.ResponseWriter, r *http.Request) {
	var req createUserRequest
	if err := utils.DecodeJSON(r, &req); err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid JSON request body")
		return
	}

	name := strings.TrimSpace(req.Name)
	mobile := utils.NormalizeMobile(req.Mobile)
	role := strings.ToLower(strings.TrimSpace(req.Role))
	if role == "" {
		role = models.RoleEmployee
	}

	if name == "" || mobile == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "name and mobile are required")
		return
	}
	if role != models.RoleAdmin && role != models.RoleEmployee {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "role must be 'admin' or 'employee'")
		return
	}

	user, err := h.userRepo.CreateUser(r.Context(), name, mobile, role)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") || strings.Contains(strings.ToLower(err.Error()), "duplicate") {
			utils.WriteError(w, http.StatusConflict, utils.ErrCodeBadRequest, "A user with this mobile number already exists")
			return
		}
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to create user")
		return
	}

	utils.WriteJSON(w, http.StatusCreated, user)
}

// ListUsers handles GET /v1/admin/users.
func (h *AdminHandler) ListUsers(w http.ResponseWriter, r *http.Request) {
	users, err := h.userRepo.ListUsers(r.Context())
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to list users")
		return
	}
	utils.WriteJSON(w, http.StatusOK, users)
}

type updateStatusRequest struct {
	Status string `json:"status"`
}

// UpdateUserStatus handles PATCH /v1/admin/users/{id}/status.
// Setting status to 'blocked' atomically revokes all active sessions for that user and invalidates the auth cache.
func (h *AdminHandler) UpdateUserStatus(w http.ResponseWriter, r *http.Request) {
	userID := strings.TrimSpace(r.PathValue("id"))
	if userID == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Missing user id in path")
		return
	}

	var req updateStatusRequest
	if err := utils.DecodeJSON(r, &req); err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid JSON request body")
		return
	}

	status := strings.ToLower(strings.TrimSpace(req.Status))
	if status != models.StatusActive && status != models.StatusBlocked {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "status must be strictly 'active' or 'blocked'")
		return
	}

	user, err := h.userRepo.UpdateUserStatus(r.Context(), userID, status)
	if errors.Is(err, repositories.ErrUserNotFound) {
		utils.WriteError(w, http.StatusNotFound, utils.ErrCodeBadRequest, "User not found")
		return
	}
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to update user status")
		return
	}

	h.authMiddleware.InvalidateUser(userID)
	utils.WriteJSON(w, http.StatusOK, user)
}

type updateRoleRequest struct {
	Role string `json:"role"`
}

// UpdateUserRole handles PATCH /v1/admin/users/{id}/role.
func (h *AdminHandler) UpdateUserRole(w http.ResponseWriter, r *http.Request) {
	userID := strings.TrimSpace(r.PathValue("id"))
	if userID == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Missing user id in path")
		return
	}

	var req updateRoleRequest
	if err := utils.DecodeJSON(r, &req); err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid JSON request body")
		return
	}

	role := strings.ToLower(strings.TrimSpace(req.Role))
	if role != models.RoleAdmin && role != models.RoleEmployee {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "role must be 'admin' or 'employee'")
		return
	}

	user, err := h.userRepo.UpdateUserRole(r.Context(), userID, role)
	if errors.Is(err, repositories.ErrUserNotFound) {
		utils.WriteError(w, http.StatusNotFound, utils.ErrCodeBadRequest, "User not found")
		return
	}
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to update user role")
		return
	}

	h.authMiddleware.InvalidateUser(userID)
	utils.WriteJSON(w, http.StatusOK, user)
}

// ListUserSessions handles GET /v1/admin/users/{id}/sessions.
func (h *AdminHandler) ListUserSessions(w http.ResponseWriter, r *http.Request) {
	userID := strings.TrimSpace(r.PathValue("id"))
	if userID == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Missing user id in path")
		return
	}

	sessions, err := h.userRepo.ListUserSessions(r.Context(), userID)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to list user sessions")
		return
	}
	utils.WriteJSON(w, http.StatusOK, sessions)
}

// RevokeSession handles DELETE /v1/admin/sessions/{session_id}.
func (h *AdminHandler) RevokeSession(w http.ResponseWriter, r *http.Request) {
	sessionID := strings.TrimSpace(r.PathValue("session_id"))
	if sessionID == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Missing session_id in path")
		return
	}

	userID, err := h.userRepo.RevokeSessionByID(r.Context(), sessionID)
	if errors.Is(err, repositories.ErrSessionNotFound) {
		utils.WriteError(w, http.StatusNotFound, utils.ErrCodeBadRequest, "Session not found")
		return
	}
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to revoke session")
		return
	}

	h.authMiddleware.InvalidateSession(sessionID)
	if userID != "" {
		h.authMiddleware.InvalidateUser(userID)
	}

	utils.WriteJSON(w, http.StatusOK, map[string]string{
		"message":    "session revoked",
		"session_id": sessionID,
	})
}

// CreateRelease handles POST /v1/admin/releases via multipart/form-data:
// Fields: apk_file, version_code, version_name, is_force_update, min_supported_version_code, release_notes
func (h *AdminHandler) CreateRelease(w http.ResponseWriter, r *http.Request) {
	// Limit max upload size to 200 MB
	r.Body = http.MaxBytesReader(w, r.Body, 200<<20)
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid multipart/form-data payload: "+err.Error())
		return
	}

	versionCodeStr := strings.TrimSpace(r.FormValue("version_code"))
	versionCode, err := strconv.Atoi(versionCodeStr)
	if err != nil || versionCode <= 0 {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "version_code must be a positive integer")
		return
	}

	versionName := strings.TrimSpace(r.FormValue("version_name"))
	if versionName == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "version_name is required (e.g. 1.1.0)")
		return
	}

	isForceUpdate := strings.EqualFold(strings.TrimSpace(r.FormValue("is_force_update")), "true") ||
		strings.TrimSpace(r.FormValue("is_force_update")) == "1"

	minSupportedCode := 1
	if minStr := strings.TrimSpace(r.FormValue("min_supported_version_code")); minStr != "" {
		parsedMin, err := strconv.Atoi(minStr)
		if err != nil || parsedMin <= 0 {
			utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "min_supported_version_code must be a positive integer")
			return
		}
		minSupportedCode = parsedMin
	} else if isForceUpdate {
		minSupportedCode = versionCode
	}

	releaseNotes := strings.TrimSpace(r.FormValue("release_notes"))

	file, _, err := r.FormFile("apk_file")
	if err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "apk_file is required in multipart/form-data")
		return
	}
	defer file.Close()

	if err := os.MkdirAll(h.cfg.APKStorageDir, 0o755); err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to prepare APK storage directory")
		return
	}

	destFilename := fmt.Sprintf("stock-v%d.apk", versionCode)
	destPath := filepath.Join(h.cfg.APKStorageDir, destFilename)
	tmpPath := destPath + ".tmp"

	outFile, err := os.Create(tmpPath)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to create APK file on disk")
		return
	}

	hasher := sha256.New()
	multiWriter := io.MultiWriter(outFile, hasher)
	written, copyErr := io.Copy(multiWriter, file)
	closeErr := outFile.Close()
	if copyErr != nil || closeErr != nil || written == 0 {
		_ = os.Remove(tmpPath)
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to write uploaded APK binary")
		return
	}

	if err := os.Rename(tmpPath, destPath); err != nil {
		_ = os.Remove(tmpPath)
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to finalize APK binary storage")
		return
	}

	apkSha256 := hex.EncodeToString(hasher.Sum(nil))
	apkDownloadURL := fmt.Sprintf("%s/v1/app/download/%d", h.cfg.PublicBaseURL, versionCode)

	release, err := h.releaseRepo.CreateRelease(
		r.Context(),
		versionCode,
		versionName,
		apkDownloadURL,
		apkSha256,
		releaseNotes,
		isForceUpdate,
		minSupportedCode,
	)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to save release metadata in database")
		return
	}

	utils.WriteJSON(w, http.StatusCreated, release)
}
