package config

import (
	"fmt"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/joho/godotenv"
)

var validSQLIdentifier = regexp.MustCompile(`^[A-Za-z0-9_.\[\]]+$`)

// Config holds runtime settings for the Habron SQL Server -> PostgreSQL Integrator Worker.
type Config struct {
	HabronMSSQLDSN        string
	HabronSourceView      string
	SupabaseDatabaseURL   string
	SyncPollInterval      time.Duration
	FullReconcileInterval time.Duration
	BatchSize             int
	WindowsServiceName    string
}

// Load reads environment variables (and .env if present) and validates required connection settings.
func Load() (*Config, error) {
	_ = godotenv.Load()

	mssqlDSN := strings.TrimSpace(os.Getenv("HABRON_MSSQL_DSN"))
	if mssqlDSN == "" {
		return nil, fmt.Errorf("HABRON_MSSQL_DSN environment variable is required")
	}
	// Ensure read-only intent is set on the SQL Server connection string if using URL format
	if strings.HasPrefix(strings.ToLower(mssqlDSN), "sqlserver://") &&
		!strings.Contains(strings.ToLower(mssqlDSN), "applicationintent=") {
		sep := "?"
		if strings.Contains(mssqlDSN, "?") {
			sep = "&"
		}
		mssqlDSN = mssqlDSN + sep + "ApplicationIntent=ReadOnly"
	}

	sourceView := strings.TrimSpace(os.Getenv("HABRON_SOURCE_VIEW"))
	if sourceView == "" {
		sourceView = "dbo.vw_PoshakReadyStockInventory"
	}
	if !validSQLIdentifier.MatchString(sourceView) {
		return nil, fmt.Errorf("invalid HABRON_SOURCE_VIEW identifier: %s", sourceView)
	}

	pgURL := strings.TrimSpace(os.Getenv("SUPABASE_DATABASE_URL"))
	if pgURL == "" {
		pgURL = strings.TrimSpace(os.Getenv("DATABASE_URL"))
	}
	if pgURL == "" {
		return nil, fmt.Errorf("SUPABASE_DATABASE_URL (or DATABASE_URL) environment variable is required")
	}

	pollInterval := 30 * time.Second
	if raw := strings.TrimSpace(os.Getenv("SYNC_POLL_INTERVAL")); raw != "" {
		if d, err := time.ParseDuration(raw); err == nil && d >= 5*time.Second {
			pollInterval = d
		}
	}

	reconcileInterval := 30 * time.Minute
	if raw := strings.TrimSpace(os.Getenv("FULL_RECONCILE_INTERVAL")); raw != "" {
		if d, err := time.ParseDuration(raw); err == nil && d >= 1*time.Minute {
			reconcileInterval = d
		}
	}

	batchSize := 500
	if raw := strings.TrimSpace(os.Getenv("BATCH_SIZE")); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed >= 50 && parsed <= 5000 {
			batchSize = parsed
		}
	}

	svcName := strings.TrimSpace(os.Getenv("WINDOWS_SERVICE_NAME"))
	if svcName == "" {
		svcName = "PoshakStockHabronIntegrator"
	}

	return &Config{
		HabronMSSQLDSN:        mssqlDSN,
		HabronSourceView:      sourceView,
		SupabaseDatabaseURL:   pgURL,
		SyncPollInterval:      pollInterval,
		FullReconcileInterval: reconcileInterval,
		BatchSize:             batchSize,
		WindowsServiceName:    svcName,
	}, nil
}
