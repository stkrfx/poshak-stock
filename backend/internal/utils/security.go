package utils

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// AccessTokenClaims represents the JWT payload for authenticated API calls.
type AccessTokenClaims struct {
	UserID    string `json:"user_id"`
	Role      string `json:"role"`
	SessionID string `json:"session_id"`
	jwt.RegisteredClaims
}

// GenerateAccessToken signs a new HMAC-SHA256 JWT access token.
func GenerateAccessToken(secret []byte, userID, role, sessionID string, ttl time.Duration) (string, error) {
	now := time.Now().UTC()
	claims := AccessTokenClaims{
		UserID:    userID,
		Role:      role,
		SessionID: sessionID,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID,
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(secret)
}

// ParseAccessToken validates the HMAC-SHA256 signature and expiration of a JWT.
func ParseAccessToken(secret []byte, tokenStr string) (*AccessTokenClaims, error) {
	token, err := jwt.ParseWithClaims(tokenStr, &AccessTokenClaims{}, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return secret, nil
	})
	if err != nil {
		return nil, err
	}
	claims, ok := token.Claims.(*AccessTokenClaims)
	if !ok || !token.Valid {
		return nil, fmt.Errorf("invalid access token claims")
	}
	return claims, nil
}

// GenerateOpaqueRefreshToken creates a crypto-random 64-byte hex string (512 bits) and its SHA-256 hex hash.
func GenerateOpaqueRefreshToken() (rawToken string, tokenHash string, err error) {
	buf := make([]byte, 64)
	if _, err := rand.Read(buf); err != nil {
		return "", "", fmt.Errorf("failed to generate random token: %w", err)
	}
	rawToken = hex.EncodeToString(buf)
	tokenHash = HashRefreshToken(rawToken)
	return rawToken, tokenHash, nil
}

// HashRefreshToken computes the SHA-256 hex digest of an opaque refresh token.
func HashRefreshToken(rawToken string) string {
	sum := sha256.Sum256([]byte(rawToken))
	return hex.EncodeToString(sum[:])
}

// PadConstantTime sleeps until minDuration has elapsed since startTime to prevent timing enumeration.
func PadConstantTime(startTime time.Time, minDuration time.Duration) {
	elapsed := time.Since(startTime)
	if elapsed < minDuration {
		time.Sleep(minDuration - elapsed)
	}
}

// SlidingWindowLimiter enforces per-key rate limits over a rolling time window.
type SlidingWindowLimiter struct {
	mu      sync.Mutex
	window  time.Duration
	maxReqs int
	hits    map[string][]time.Time
}

// NewSlidingWindowLimiter constructs a rate limiter with the given max requests per window.
func NewSlidingWindowLimiter(maxReqs int, window time.Duration) *SlidingWindowLimiter {
	return &SlidingWindowLimiter{
		window:  window,
		maxReqs: maxReqs,
		hits:    make(map[string][]time.Time),
	}
}

// Allow checks if key is within rate limits and records the current hit if allowed.
func (l *SlidingWindowLimiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	cutoff := now.Add(-l.window)

	existing := l.hits[key]
	valid := existing[:0]
	for _, ts := range existing {
		if ts.After(cutoff) {
			valid = append(valid, ts)
		}
	}

	if len(valid) >= l.maxReqs {
		l.hits[key] = valid
		return false
	}

	l.hits[key] = append(valid, now)
	return true
}