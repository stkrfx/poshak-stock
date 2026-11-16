package middleware

import (
	"context"
	"crypto/subtle"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/stkrfx/stock-backend/internal/config"
	"github.com/stkrfx/stock-backend/internal/models"
	"github.com/stkrfx/stock-backend/internal/repositories"
	"github.com/stkrfx/stock-backend/internal/utils"
)

type contextKey string

const (
	ctxKeyUserID    contextKey = "auth_user_id"
	ctxKeyUserRole  contextKey = "auth_user_role"
	ctxKeySessionID contextKey = "auth_session_id"
)

type cachedSessionState struct {
	userID         string
	role           string
	userStatus     string
	sessionRevoked bool
	expiresAt      time.Time
}

// AuthMiddleware enforces JWT authentication, live session/user status checks, and admin authorization.
type AuthMiddleware struct {
	cfg      *config.Config
	userRepo *repositories.UserSessionRepository
	mu       sync.RWMutex
	cache    map[string]cachedSessionState
}

func NewAuthMiddleware(cfg *config.Config, userRepo *repositories.UserSessionRepository) *AuthMiddleware {
	return &AuthMiddleware{
		cfg:      cfg,
		userRepo: userRepo,
		cache:    make(map[string]cachedSessionState),
	}
}

// InvalidateUser removes all cached session states for a given userID immediately
// (called when an admin updates user status or role).
func (m *AuthMiddleware) InvalidateUser(userID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for sid, entry := range m.cache {
		if entry.userID == userID {
			delete(m.cache, sid)
		}
	}
}

// InvalidateSession removes a specific sessionID from the cache immediately
// (called when an admin revokes a session or user logs out).
func (m *AuthMiddleware) InvalidateSession(sessionID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.cache, sessionID)
}

// RequireAuth verifies the Bearer JWT token and ensures the session is active and user is 'active'.
func (m *AuthMiddleware) RequireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tokenStr, ok := extractBearerToken(r)
		if !ok {
			utils.WriteError(w, http.StatusUnauthorized, utils.ErrCodeUnauthorized, "Missing or invalid Authorization Bearer header")
			return
		}

		claims, err := utils.ParseAccessToken(m.cfg.JWTSecret, tokenStr)
		if err != nil {
			utils.WriteError(w, http.StatusUnauthorized, utils.ErrCodeUnauthorized, "Invalid or expired access token")
			return
		}

		userID, role, userStatus, sessionRevoked, err := m.verifySessionState(r.Context(), claims.SessionID)
		if err != nil {
			utils.WriteError(w, http.StatusUnauthorized, utils.ErrCodeSessionRevoked, "Session is no longer valid")
			return
		}

		if userStatus == models.StatusBlocked {
			utils.WriteError(w, http.StatusForbidden, utils.ErrCodeAccountBlocked, "Your account has been deactivated. Contact the shop owner.")
			return
		}

		if sessionRevoked {
			utils.WriteError(w, http.StatusUnauthorized, utils.ErrCodeSessionRevoked, "Your session has been revoked. Please log in again.")
			return
		}

		ctx := context.WithValue(r.Context(), ctxKeyUserID, userID)
		ctx = context.WithValue(ctx, ctxKeyUserRole, role)
		ctx = context.WithValue(ctx, ctxKeySessionID, claims.SessionID)
		next(w, r.WithContext(ctx))
	}
}

// RequireAdmin authorizes Hoppscotch admin calls via either X-Admin-API-Key OR an active Admin Bearer JWT.
func (m *AuthMiddleware) RequireAdmin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// 1. Check X-Admin-API-Key header for direct Hoppscotch access
		if m.cfg.AdminMasterKey != "" {
			providedKey := strings.TrimSpace(r.Header.Get("X-Admin-API-Key"))
			if providedKey != "" {
				if subtle.ConstantTimeCompare([]byte(providedKey), []byte(m.cfg.AdminMasterKey)) == 1 {
					ctx := context.WithValue(r.Context(), ctxKeyUserID, "hoppscotch-master-admin")
					ctx = context.WithValue(ctx, ctxKeyUserRole, models.RoleAdmin)
					next(w, r.WithContext(ctx))
					return
				}
				utils.WriteError(w, http.StatusForbidden, utils.ErrCodeForbidden, "Invalid X-Admin-API-Key")
				return
			}
		}

		// 2. Otherwise require a valid Admin JWT
		if m.cfg.AdminSecret != "" {
			providedSecret := strings.TrimSpace(r.Header.Get("X-Admin-Secret"))
			if subtle.ConstantTimeCompare([]byte(providedSecret), []byte(m.cfg.AdminSecret)) != 1 {
				utils.WriteError(w, http.StatusForbidden, utils.ErrCodeForbidden, "Invalid or missing X-Admin-Secret header")
				return
			}
		}

		m.RequireAuth(func(w http.ResponseWriter, r *http.Request) {
			if GetUserRole(r.Context()) != models.RoleAdmin {
				utils.WriteError(w, http.StatusForbidden, utils.ErrCodeForbidden, "Admin privileges required")
				return
			}
			next(w, r)
		})(w, r)
	}
}

func (m *AuthMiddleware) verifySessionState(
	ctx context.Context, sessionID string,
) (userID string, role string, userStatus string, sessionRevoked bool, err error) {
	now := time.Now()

	m.mu.RLock()
	cached, found := m.cache[sessionID]
	m.mu.RUnlock()

	if found && now.Before(cached.expiresAt) {
		return cached.userID, cached.role, cached.userStatus, cached.sessionRevoked, nil
	}

	userID, role, userStatus, sessionRevoked, err = m.userRepo.ValidateSessionAndUser(ctx, sessionID)
	if err != nil {
		return "", "", "", true, err
	}

	m.mu.Lock()
	m.cache[sessionID] = cachedSessionState{
		userID:         userID,
		role:           role,
		userStatus:     userStatus,
		sessionRevoked: sessionRevoked,
		expiresAt:      now.Add(15 * time.Second),
	}
	m.mu.Unlock()

	return userID, role, userStatus, sessionRevoked, nil
}

func extractBearerToken(r *http.Request) (string, bool) {
	authHeader := strings.TrimSpace(r.Header.Get("Authorization"))
	if authHeader == "" {
		return "", false
	}
	parts := strings.SplitN(authHeader, " ", 2)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
		return "", false
	}
	token := strings.TrimSpace(parts[1])
	return token, token != ""
}

// GetUserID extracts the authenticated user's UUID from the request context.
func GetUserID(ctx context.Context) string {
	val, _ := ctx.Value(ctxKeyUserID).(string)
	return val
}

// GetUserRole extracts the authenticated user's role ('admin' or 'employee') from the request context.
func GetUserRole(ctx context.Context) string {
	val, _ := ctx.Value(ctxKeyUserRole).(string)
	return val
}

// GetSessionID extracts the authenticated session UUID from the request context.
func GetSessionID(ctx context.Context) string {
	val, _ := ctx.Value(ctxKeySessionID).(string)
	return val
}