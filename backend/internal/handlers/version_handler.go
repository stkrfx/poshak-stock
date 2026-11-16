package handlers

import (
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/stkrfx/stock-backend/internal/config"
	"github.com/stkrfx/stock-backend/internal/models"
	"github.com/stkrfx/stock-backend/internal/repositories"
	"github.com/stkrfx/stock-backend/internal/utils"
)

// VersionHandler implements GET /v1/app/version and GET /v1/app/download/{version_code}.
type VersionHandler struct {
	cfg         *config.Config
	releaseRepo *repositories.ReleaseRepository
}

func NewVersionHandler(cfg *config.Config, releaseRepo *repositories.ReleaseRepository) *VersionHandler {
	return &VersionHandler{
		cfg:         cfg,
		releaseRepo: releaseRepo,
	}
}

// CheckVersion handles GET /v1/app/version?current_version_code=1.
func (h *VersionHandler) CheckVersion(w http.ResponseWriter, r *http.Request) {
	currentVersionStr := strings.TrimSpace(r.URL.Query().Get("current_version_code"))
	currentVersionCode := 0
	if currentVersionStr != "" {
		parsed, err := strconv.Atoi(currentVersionStr)
		if err != nil || parsed < 0 {
			utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "current_version_code must be a non-negative integer")
			return
		}
		currentVersionCode = parsed
	}

	latest, err := h.releaseRepo.GetLatestRelease(r.Context())
	if errors.Is(err, repositories.ErrReleaseNotFound) {
		utils.WriteJSON(w, http.StatusOK, models.VersionCheckResponse{
			LatestVersionCode: currentVersionCode,
			LatestVersionName: "1.0.0",
			APKDownloadURL:    "",
			APKSha256:         "",
			ReleaseNotes:      "",
			UpdateAvailable:   false,
			ForceUpdate:       false,
		})
		return
	}
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to check latest app version")
		return
	}

	updateAvailable := latest.VersionCode > currentVersionCode
	forceUpdate := updateAvailable && (latest.IsForceUpdate || currentVersionCode < latest.MinSupportedVersionCode)

	utils.WriteJSON(w, http.StatusOK, models.VersionCheckResponse{
		LatestVersionCode: latest.VersionCode,
		LatestVersionName: latest.VersionName,
		APKDownloadURL:    latest.APKDownloadURL,
		APKSha256:         latest.APKSha256,
		ReleaseNotes:      latest.ReleaseNotes,
		UpdateAvailable:   updateAvailable,
		ForceUpdate:       forceUpdate,
	})
}

// DownloadAPK handles GET /v1/app/download/{version_code}.
// Streams the signed APK binary with Content-Length and SHA-256 headers for in-app update progress & verification.
func (h *VersionHandler) DownloadAPK(w http.ResponseWriter, r *http.Request) {
	vcStr := strings.TrimSpace(r.PathValue("version_code"))
	versionCode, err := strconv.Atoi(vcStr)
	if err != nil || versionCode <= 0 {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid version_code parameter")
		return
	}

	rel, err := h.releaseRepo.GetReleaseByVersionCode(r.Context(), versionCode)
	if errors.Is(err, repositories.ErrReleaseNotFound) {
		utils.WriteError(w, http.StatusNotFound, utils.ErrCodeBadRequest, "Requested APK release version not found")
		return
	}
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to look up APK release metadata")
		return
	}

	apkFilename := fmt.Sprintf("stock-v%d.apk", rel.VersionCode)
	apkPath := filepath.Join(h.cfg.APKStorageDir, apkFilename)

	file, err := os.Open(apkPath)
	if err != nil {
		utils.WriteError(w, http.StatusNotFound, utils.ErrCodeBadRequest, "APK binary file is missing on server storage")
		return
	}
	defer file.Close()

	stat, err := file.Stat()
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to inspect APK binary size")
		return
	}

	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", apkFilename))
	w.Header().Set("Content-Length", strconv.FormatInt(stat.Size(), 10))
	w.Header().Set("X-APK-SHA256", rel.APKSha256)

	http.ServeContent(w, r, apkFilename, stat.ModTime(), file)
}
