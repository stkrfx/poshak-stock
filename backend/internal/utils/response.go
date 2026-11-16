package utils

import (
	"encoding/json"
	"net"
	"net/http"
	"strings"
)

// Explicit API error codes used by the Android client to trigger security actions.
const (
	ErrCodeSessionRevoked = "session_revoked"
	ErrCodeAccountBlocked = "account_blocked"
	ErrCodeNotAllowed     = "not_allowed"
	ErrCodeUnauthorized   = "unauthorized"
	ErrCodeForbidden      = "forbidden"
	ErrCodeBadRequest     = "bad_request"
	ErrCodeRateLimited    = "rate_limited"
	ErrCodeInternal       = "internal_error"
)

// APIError is the standard error envelope returned by the backend.
type APIError struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

// WriteJSON serializes data as JSON with the given HTTP status code.
func WriteJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

// WriteError writes a structured JSON error payload.
func WriteError(w http.ResponseWriter, status int, errCode string, message string) {
	WriteJSON(w, status, APIError{
		Error:   errCode,
		Message: message,
	})
}

// DecodeJSON decodes a JSON request body strictly into dst.
func DecodeJSON(r *http.Request, dst any) error {
	defer r.Body.Close()
	dec := json.NewDecoder(r.Body)
	return dec.Decode(dst)
}

// ExtractClientIP extracts the caller's IP address, respecting X-Forwarded-For / X-Real-IP.
func ExtractClientIP(r *http.Request) string {
	if xff := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); xff != "" {
		parts := strings.Split(xff, ",")
		if ip := strings.TrimSpace(parts[0]); ip != "" {
			return ip
		}
	}
	if xrip := strings.TrimSpace(r.Header.Get("X-Real-IP")); xrip != "" {
		return xrip
	}
	host, _, err := net.SplitHostPort(strings.TrimSpace(r.RemoteAddr))
	if err == nil && host != "" {
		return host
	}
	return r.RemoteAddr
}

// NormalizeMobile trims whitespace and ensures a consistent E.164-style format (+91XXXXXXXXXX).
func NormalizeMobile(mobile string) string {
	cleaned := strings.ReplaceAll(strings.TrimSpace(mobile), " ", "")
	cleaned = strings.ReplaceAll(cleaned, "-", "")
	if len(cleaned) == 10 && cleaned[0] >= '6' && cleaned[0] <= '9' {
		return "+91" + cleaned
	}
	return cleaned
}