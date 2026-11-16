package syncer

import (
	"context"
	"log"
	"math"
	"time"

	"github.com/stkrfx/stock-integrator/internal/config"
	"github.com/stkrfx/stock-integrator/internal/source"
	"github.com/stkrfx/stock-integrator/internal/target"
)

// Orchestrator coordinates incremental and full-reconciliation sync cycles between
// Habron SQL Server (read-only) and Supabase PostgreSQL with exponential-backoff recovery.
type Orchestrator struct {
	cfg               *config.Config
	extractor         *source.MSSQLExtractor
	writer            *target.PostgresWriter
	lastFullReconcile time.Time
}

func NewOrchestrator(
	cfg *config.Config,
	extractor *source.MSSQLExtractor,
	writer *target.PostgresWriter,
) *Orchestrator {
	return &Orchestrator{
		cfg:       cfg,
		extractor: extractor,
		writer:    writer,
	}
}

// RunLoop continuously runs sync cycles at cfg.SyncPollInterval until ctx is cancelled,
// applying exponential backoff on transient database or network errors.
func (o *Orchestrator) RunLoop(ctx context.Context) error {
	log.Printf("[INFO] Starting Habron -> Supabase Integrator loop (poll=%s, fullReconcile=%s, batchSize=%d)",
		o.cfg.SyncPollInterval, o.cfg.FullReconcileInterval, o.cfg.BatchSize)

	consecutiveFailures := 0

	for {
		if ctx.Err() != nil {
			return nil
		}

		err := o.RunSingleCycle(ctx)
		if err != nil {
			consecutiveFailures++
			log.Printf("[ERROR] Sync cycle failed (attempt #%d): %v", consecutiveFailures, err)
			o.writer.RecordSyncFailure(ctx, err.Error())

			backoff := calculateExponentialBackoff(consecutiveFailures, o.cfg.SyncPollInterval)
			log.Printf("[WARN] Retrying next sync cycle after backoff of %s...", backoff)

			select {
			case <-ctx.Done():
				return nil
			case <-time.After(backoff):
				continue
			}
		}

		consecutiveFailures = 0

		select {
		case <-ctx.Done():
			return nil
		case <-time.After(o.cfg.SyncPollInterval):
		}
	}
}

// RunSingleCycle executes a single sync cycle (either a full snapshot reconciliation
// on initial load / reconcile interval, or an incremental cursor sync).
func (o *Orchestrator) RunSingleCycle(ctx context.Context) error {
	lastCursor, err := o.writer.GetLastSyncedCursor(ctx)
	if err != nil {
		return err
	}

	now := time.Now()
	needFullReconcile := lastCursor.IsZero() ||
		o.lastFullReconcile.IsZero() ||
		now.Sub(o.lastFullReconcile) >= o.cfg.FullReconcileInterval

	var extractSince time.Time
	if !needFullReconcile {
		extractSince = lastCursor
	}

	records, maxUpdated, err := o.extractor.ExtractProducts(ctx, extractSince)
	if err != nil {
		return err
	}

	if len(records) == 0 && !needFullReconcile {
		// Still mark sync_metadata healthy even when zero rows changed in Habron
		_, err := o.writer.ApplyExtractedProducts(ctx, nil, lastCursor, false)
		return err
	}

	stats, err := o.writer.ApplyExtractedProducts(ctx, records, maxUpdated, needFullReconcile)
	if err != nil {
		return err
	}

	if needFullReconcile {
		o.lastFullReconcile = now
	}

	log.Printf(
		"[INFO] Sync cycle complete (full=%v, duration=%s): upserted %d products, %d stock rows, %d lookups | deleted %d products, %d stocks | cursor=%s",
		stats.IsFullReconciliation,
		stats.Duration.Round(time.Millisecond),
		stats.ProductsUpserted,
		stats.StocksUpserted,
		stats.LookupsUpserted,
		stats.ProductsDeleted,
		stats.StocksDeleted,
		stats.MaxSourceUpdatedAt.UTC().Format(time.RFC3339),
	)

	return nil
}

func calculateExponentialBackoff(failures int, baseInterval time.Duration) time.Duration {
	if failures <= 0 {
		return baseInterval
	}
	cappedExp := math.Min(float64(failures), 6.0)
	multiplier := math.Pow(2.0, cappedExp-1.0)
	backoff := time.Duration(float64(5*time.Second) * multiplier)
	maxBackoff := 5 * time.Minute
	if backoff > maxBackoff {
		return maxBackoff
	}
	return backoff
}
