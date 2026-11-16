package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/stkrfx/stock-integrator/internal/config"
	"github.com/stkrfx/stock-integrator/internal/service"
	"github.com/stkrfx/stock-integrator/internal/source"
	"github.com/stkrfx/stock-integrator/internal/syncer"
	"github.com/stkrfx/stock-integrator/internal/target"
)

func main() {
	// Ensure working directory is the executable's folder when launched by Windows Service Control Manager
	if exePath, err := os.Executable(); err == nil {
		_ = os.Chdir(filepath.Dir(exePath))
	}

	var (
		installFlag = flag.Bool("install", false, "Install as an automatic-start Windows Service")
		removeFlag  = flag.Bool("remove", false, "Uninstall the Windows Service")
		runOnceFlag = flag.Bool("once", false, "Run a single Habron -> PostgreSQL sync cycle and exit")
	)
	flag.Parse()

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("[FATAL] Configuration error: %v", err)
	}

	if *installFlag {
		err := service.InstallWindowsService(
			cfg.WindowsServiceName,
			"Poshak Stock Habron Integrator",
			"Extracts ready-to-store product and stock inventory from Habron SQL Server into Supabase PostgreSQL.",
		)
		if err != nil {
			log.Fatalf("[FATAL] Failed to install Windows Service: %v", err)
		}
		log.Printf("[INFO] Windows Service %q installed successfully.", cfg.WindowsServiceName)
		return
	}

	if *removeFlag {
		if err := service.RemoveWindowsService(cfg.WindowsServiceName); err != nil {
			log.Fatalf("[FATAL] Failed to remove Windows Service: %v", err)
		}
		log.Printf("[INFO] Windows Service %q removed successfully.", cfg.WindowsServiceName)
		return
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	extractor, err := source.NewMSSQLExtractor(ctx, cfg.HabronMSSQLDSN, cfg.HabronSourceView)
	if err != nil {
		log.Fatalf("[FATAL] Failed to initialize Habron SQL Server extractor: %v", err)
	}
	defer extractor.Close()

	writer, err := target.NewPostgresWriter(ctx, cfg.SupabaseDatabaseURL, cfg.BatchSize)
	if err != nil {
		log.Fatalf("[FATAL] Failed to initialize Supabase PostgreSQL writer: %v", err)
	}
	defer writer.Close()

	orchestrator := syncer.NewOrchestrator(cfg, extractor, writer)

	if *runOnceFlag {
		log.Println("[INFO] Running single Habron -> PostgreSQL sync cycle...")
		if err := orchestrator.RunSingleCycle(ctx); err != nil {
			log.Fatalf("[FATAL] Single sync cycle failed: %v", err)
		}
		log.Println("[INFO] Single sync cycle completed successfully.")
		return
	}

	isWinSvc, err := service.IsWindowsServiceSession()
	if err == nil && isWinSvc {
		if err := service.RunAsWindowsService(cfg.WindowsServiceName, orchestrator); err != nil {
			log.Fatalf("[FATAL] Windows Service execution failed: %v", err)
		}
		return
	}

	// Interactive console mode
	if err := orchestrator.RunLoop(ctx); err != nil {
		log.Fatalf("[FATAL] Orchestrator terminated with error: %v", err)
	}
	log.Println("[INFO] Habron Integrator stopped cleanly.")
}
