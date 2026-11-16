package handlers

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/stkrfx/stock-backend/internal/config"
	"github.com/stkrfx/stock-backend/internal/middleware"
	"github.com/stkrfx/stock-backend/internal/models"
	"github.com/stkrfx/stock-backend/internal/repositories"
	"github.com/stkrfx/stock-backend/internal/services"
	"github.com/stkrfx/stock-backend/internal/utils"
)

// AuthHandler implements /v1/auth/* endpoints (pre-login, login, refresh, logout).
type AuthHandler struct {
	cfg              *config.Config
	userRepo         *repositories.UserSessionRepository
	firebaseVerifier *services.FirebaseVerifier
	authMiddleware   *middleware.AuthMiddleware
	mobileLimiter    *utils.SlidingWindowLimiter
	ipLimiter        *utils.SlidingWindowLimiter
}

func NewAuthHandler(
	cfg *config.Config,
	userRepo *repositories.UserSessionRepository,
	firebaseVerifier *services.FirebaseVerifier,
	authMiddleware *middleware.AuthMiddleware,
) *AuthHandler {
	return &AuthHandler{
		cfg:              cfg,
		userRepo:         userRepo,
		firebaseVerifier: firebaseVerifier,
		authMiddleware:   authMiddleware,
		// Rate Limit: Max 5 req / mobile / hour; Max 15 req / IP / hour
		mobileLimiter: utils.NewSlidingWindowLimiter(5, time.Hour),
		ipLimiter:     utils.NewSlidingWindowLimiter(15, time.Hour),
	}
}

type preLoginRequest struct {
	Mobile string `json:"mobile"`
}

type preLoginResponse struct {
	Allowed bool `json:"allowed"`
}

// PreLogin handles POST /v1/auth/pre-login.
// Checks if the mobile number belongs to an existing 'active' user before Firebase Phone Auth is triggered.
// Uses constant-time response padding and identical 403 payloads for unknown vs blocked numbers.
func (h *AuthHandler) PreLogin(w http.ResponseWriter, r *http.Request) {
	startTime := time.Now()
	const minResponseTime = 120 * time.Millisecond

	var req preLoginRequest
	if err := utils.DecodeJSON(r, &req); err != nil {
		utils.PadConstantTime(startTime, minResponseTime)
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid JSON request body")
		return
	}

	mobile := utils.NormalizeMobile(req.Mobile)
	if mobile == "" {
		utils.PadConstantTime(startTime, minResponseTime)
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "mobile is required")
		return
	}

	clientIP := utils.ExtractClientIP(r)
	if !h.ipLimiter.Allow(clientIP) || !h.mobileLimiter.Allow(mobile) {
		utils.PadConstantTime(startTime, minResponseTime)
		utils.WriteError(w, http.StatusTooManyRequests, utils.ErrCodeRateLimited, "Too many pre-login attempts. Please try again later.")
		return
	}

	user, err := h.userRepo.GetUserByMobile(r.Context(), mobile)
	utils.PadConstantTime(startTime, minResponseTime)

	if err != nil || user == nil || user.Status != models.StatusActive {
		utils.WriteError(w, http.StatusForbidden, utils.ErrCodeNotAllowed, "This number is not authorized")
		return
	}

	utils.WriteJSON(w, http.StatusOK, preLoginResponse{Allowed: true})
}

type loginRequest struct {
	FirebaseIDToken string `json:"firebase_id_token"`
	DeviceID        string `json:"device_id"`
	DeviceName      string `json:"device_name"`
	DeviceType      string `json:"device_type"`
}

type loginResponse struct {
	AccessToken  string             `json:"access_token"`
	RefreshToken string             `json:"refresh_token"`
	ExpiresIn    int                `json:"expires_in"`
	User         models.UserSummary `json:"user"`
}

// Login handles POST /v1/auth/login.
// Verifies the Firebase ID token, checks that the user is 'active', creates a device session,
// and returns an access_token (JWT) + opaque refresh_token.
func (h *AuthHandler) Login(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if err := utils.DecodeJSON(r, &req); err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid JSON request body")
		return
	}

	req.DeviceID = strings.TrimSpace(req.DeviceID)
	req.DeviceName = strings.TrimSpace(req.DeviceName)
	req.DeviceType = strings.TrimSpace(req.DeviceType)
	if req.DeviceType == "" {
		req.DeviceType = "android"
	}

	if strings.TrimSpace(req.FirebaseIDToken) == "" || req.DeviceID == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "firebase_id_token and device_id are required")
		return
	}

	verifiedPhone, err := h.firebaseVerifier.VerifyPhoneToken(r.Context(), req.FirebaseIDToken)
	if err != nil {
		utils.WriteError(w, http.StatusUnauthorized, utils.ErrCodeUnauthorized, "Failed to verify Firebase ID token")
		return
	}

	normalizedMobile := utils.NormalizeMobile(verifiedPhone)
	user, err := h.userRepo.GetUserByMobile(r.Context(), normalizedMobile)
	if errors.Is(err, repositories.ErrUserNotFound) || user == nil {
		utils.WriteError(w, http.StatusForbidden, utils.ErrCodeNotAllowed, "This number is not authorized")
		return
	}
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to look up user")
		return
	}

	if user.Status == models.StatusBlocked {
		utils.WriteError(w, http.StatusForbidden, utils.ErrCodeAccountBlocked, "Your account has been deactivated. Contact the shop owner.")
		return
	}

	rawRefresh, refreshHash, err := utils.GenerateOpaqueRefreshToken()
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to generate session token")
		return
	}

	clientIP := utils.ExtractClientIP(r)
	userAgent := strings.TrimSpace(r.Header.Get("User-Agent"))

	session, err := h.userRepo.CreateSession(
		r.Context(),
		user.ID,
		refreshHash,
		req.DeviceID,
		req.DeviceName,
		req.DeviceType,
		clientIP,
		userAgent,
	)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to create device session")
		return
	}

	accessToken, err := utils.GenerateAccessToken(
		h.cfg.JWTSecret,
		user.ID,
		user.Role,
		session.ID,
		h.cfg.AccessTokenTTL,
	)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to sign access token")
		return
	}

	utils.WriteJSON(w, http.StatusOK, loginResponse{
		AccessToken:  accessToken,
		RefreshToken: rawRefresh,
		ExpiresIn:    int(h.cfg.AccessTokenTTL.Seconds()),
		User: models.UserSummary{
			ID:     user.ID,
			Name:   user.Name,
			Mobile: user.Mobile,
			Role:   user.Role,
		},
	})
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
	DeviceID     string `json:"device_id"`
}

type refreshResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int    `json:"expires_in"`
}

// Refresh handles POST /v1/auth/refresh.
// Atomically locks the session row, verifies user status and device binding, and rotates the refresh token.
func (h *AuthHandler) Refresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if err := utils.DecodeJSON(r, &req); err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid JSON request body")
		return
	}

	req.RefreshToken = strings.TrimSpace(req.RefreshToken)
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if req.RefreshToken == "" || req.DeviceID == "" {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "refresh_token and device_id are required")
		return
	}

	oldHash := utils.HashRefreshToken(req.RefreshToken)
	newRawRefresh, newHash, err := utils.GenerateOpaqueRefreshToken()
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to generate refresh token")
		return
	}

	clientIP := utils.ExtractClientIP(r)
	userAgent := strings.TrimSpace(r.Header.Get("User-Agent"))

	user, sessionID, err := h.userRepo.RotateRefreshToken(
		r.Context(),
		oldHash,
		newHash,
		req.DeviceID,
		clientIP,
		userAgent,
	)
	if err != nil {
		if errors.Is(err, repositories.ErrAccountBlocked) {
			utils.WriteError(w, http.StatusForbidden, utils.ErrCodeAccountBlocked, "Your account has been deactivated. Contact the shop owner.")
			return
		}
		if errors.Is(err, repositories.ErrSessionRevoked) || errors.Is(err, repositories.ErrDeviceMismatch) {
			utils.WriteError(w, http.StatusUnauthorized, utils.ErrCodeSessionRevoked, "Your session has been revoked. Please log in again.")
			return
		}
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to refresh token")
		return
	}

	accessToken, err := utils.GenerateAccessToken(
		h.cfg.JWTSecret,
		user.ID,
		user.Role,
		sessionID,
		h.cfg.AccessTokenTTL,
	)
	if err != nil {
		utils.WriteError(w, http.StatusInternalServerError, utils.ErrCodeInternal, "Failed to issue access token")
		return
	}

	utils.WriteJSON(w, http.StatusOK, refreshResponse{
		AccessToken:  accessToken,
		RefreshToken: newRawRefresh,
		ExpiresIn:    int(h.cfg.AccessTokenTTL.Seconds()),
	})
}

type logoutRequest struct {
	RefreshToken string `json:"refresh_token"`
}

// Logout handles POST /v1/auth/logout.
func (h *AuthHandler) Logout(w http.ResponseWriter, r *http.Request) {
	var req logoutRequest
	if err := utils.DecodeJSON(r, &req); err != nil {
		utils.WriteError(w, http.StatusBadRequest, utils.ErrCodeBadRequest, "Invalid JSON request body")
		return
	}

	if raw := strings.TrimSpace(req.RefreshToken); raw != "" {
		tokenHash := utils.HashRefreshToken(raw)
		_ = h.userRepo.RevokeSessionByTokenHash(r.Context(), tokenHash)
	}

	utils.WriteJSON(w, http.StatusOK, map[string]string{
		"message": "logged out",
	})
}
