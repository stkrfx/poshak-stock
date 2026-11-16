package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/joho/godotenv"
)

// Config holds all runtime configuration for the Stock Backend API server.
type Config struct {
	Port                  string
	PublicBaseURL         string
	CORSAllowedOrigins []string
	DatabaseURL           string
	JWTSecret             []byte
	AccessTokenTTL        time.Duration
	AdminMasterKey        string
	AdminSecret           string
	FirebaseCredsFile     string
	DevBypassFirebase     bool
	APKStorageDir         string
}

// Load reads environment variables (loading .env if present) and validates required settings.
func Load() (*Config, error) {
	_ = godotenv.Load()

	port := strings.TrimSpace(os.Getenv("PORT"))
	if port == "" {
		port = "8080"
	}

	publicBaseURL := strings.TrimRight(strings.TrimSpace(os.Getenv("PUBLIC_BASE_URL")), "/")
	if publicBaseURL == "" {
		publicBaseURL = "http://localhost:" + port
	}

	var corsAllowedOrigins []string

	for _, origin := range strings.Split(
		os.Getenv("CORS_ALLOWED_ORIGINS"), ",",
	) {
		origin = strings.TrimRight(strings.TrimSpace(origin), "/")

		if origin != "" {
			corsAllowedOrigins = append(corsAllowedOrigins, origin)
		}
	}

	dbURL := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if dbURL == "" {
		return nil, fmt.Errorf("DATABASE_URL environment variable is required")
	}

	jwtSecret := strings.TrimSpace(os.Getenv("JWT_SECRET"))
	if len(jwtSecret) < 32 {
		return nil, fmt.Errorf("JWT_SECRET must be at least 32 characters long")
	}

	ttlMinutes := 15
	if rawTTL := strings.TrimSpace(os.Getenv("ACCESS_TOKEN_TTL_MINUTES")); rawTTL != "" {
		if parsed, err := strconv.Atoi(rawTTL); err == nil && parsed > 0 {
			ttlMinutes = parsed
		}
	}

	apkStorageDir := strings.TrimSpace(os.Getenv("APK_STORAGE_DIR"))
	if apkStorageDir == "" {
		apkStorageDir = "./storage/apks"
	}
	if err := os.MkdirAll(apkStorageDir, 0o755); err != nil {
		return nil, fmt.Errorf("failed to create APK_STORAGE_DIR (%s): %w", apkStorageDir, err)
	}

	devBypass := strings.EqualFold(strings.TrimSpace(os.Getenv("DEV_BYPASS_FIREBASE")), "true")

	return &Config{
		Port:              port,
		PublicBaseURL:     publicBaseURL,
		CORSAllowedOrigins: corsAllowedOrigins,
		DatabaseURL:       dbURL,
		JWTSecret:         []byte(jwtSecret),
		AccessTokenTTL:    time.Duration(ttlMinutes) * time.Minute,
		AdminMasterKey:    strings.TrimSpace(os.Getenv("ADMIN_MASTER_KEY")),
		AdminSecret:       strings.TrimSpace(os.Getenv("ADMIN_SECRET")),
		FirebaseCredsFile: strings.TrimSpace(os.Getenv("FIREBASE_CREDENTIALS_FILE")),
		DevBypassFirebase: devBypass,
		APKStorageDir:     apkStorageDir,
	}, nil
}