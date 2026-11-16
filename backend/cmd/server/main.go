package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/stkrfx/stock-backend/internal/config"
	"github.com/stkrfx/stock-backend/internal/db"
	"github.com/stkrfx/stock-backend/internal/handlers"
	"github.com/stkrfx/stock-backend/internal/middleware"
	"github.com/stkrfx/stock-backend/internal/repositories"
	"github.com/stkrfx/stock-backend/internal/services"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("[FATAL] Failed to load configuration: %v", err)
	}

	pool, err := db.NewPostgresPool(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("[FATAL] Database connection failed: %v", err)
	}
	defer pool.Close()

	firebaseVerifier, err := services.NewFirebaseVerifier(cfg.FirebaseCredsFile, cfg.DevBypassFirebase)
	if err != nil {
		log.Fatalf("[FATAL] Firebase verifier initialization failed: %v", err)
	}

	// Initialize Repositories
	userRepo := repositories.NewUserSessionRepository(pool)
	stockRepo := repositories.NewStockRepository(pool)
	syncRepo := repositories.NewSyncHistoryRepository(pool)
	releaseRepo := repositories.NewReleaseRepository(pool)

	// Initialize Middleware
	authMW := middleware.NewAuthMiddleware(cfg, userRepo)

	// Initialize HTTP Handlers
	authHandler := handlers.NewAuthHandler(cfg, userRepo, firebaseVerifier, authMW)
	adminHandler := handlers.NewAdminHandler(cfg, userRepo, releaseRepo, authMW)
	stockHandler := handlers.NewStockHandler(stockRepo)
	syncHandler := handlers.NewSyncHandler(stockRepo, syncRepo)
	versionHandler := handlers.NewVersionHandler(cfg, releaseRepo)

	mux := http.NewServeMux()

	// 1. Health Endpoint (Public readiness check for Pull-to-Refresh gate)
	mux.HandleFunc("GET /v1/health", syncHandler.Health)

	// 2. Authentication Endpoints
	mux.HandleFunc("POST /v1/auth/pre-login", authHandler.PreLogin)
	mux.HandleFunc("POST /v1/auth/login", authHandler.Login)
	mux.HandleFunc("POST /v1/auth/refresh", authHandler.Refresh)
	mux.HandleFunc("POST /v1/auth/logout", authHandler.Logout)

	// 3. In-App Self-Update Endpoints (Protected)
	mux.HandleFunc("GET /v1/app/version", authMW.RequireAuth(versionHandler.CheckVersion))
	mux.HandleFunc("GET /v1/app/download/{version_code}", authMW.RequireAuth(versionHandler.DownloadAPK))

	// 4. Hoppscotch Admin Endpoints (Protected via X-Admin-API-Key OR Admin Bearer JWT)
	mux.HandleFunc("POST /v1/admin/users", authMW.RequireAdmin(adminHandler.CreateUser))
	mux.HandleFunc("GET /v1/admin/users", authMW.RequireAdmin(adminHandler.ListUsers))
	mux.HandleFunc("PATCH /v1/admin/users/{id}/status", authMW.RequireAdmin(adminHandler.UpdateUserStatus))
	mux.HandleFunc("PATCH /v1/admin/users/{id}/role", authMW.RequireAdmin(adminHandler.UpdateUserRole))
	mux.HandleFunc("GET /v1/admin/users/{id}/sessions", authMW.RequireAdmin(adminHandler.ListUserSessions))
	mux.HandleFunc("DELETE /v1/admin/sessions/{session_id}", authMW.RequireAdmin(adminHandler.RevokeSession))
	mux.HandleFunc("POST /v1/admin/releases", authMW.RequireAdmin(adminHandler.CreateRelease))

	// 5. Confidential Stock, Autocomplete, Offline Sync & Search History Endpoints (Protected)
	mux.HandleFunc("GET /v1/autocomplete", authMW.RequireAuth(stockHandler.Autocomplete))
	mux.HandleFunc("GET /v1/stock/search", authMW.RequireAuth(stockHandler.SearchStock))
	mux.HandleFunc("POST /v1/sync/offline-downloads", authMW.RequireAuth(syncHandler.OfflineDownloads))
	mux.HandleFunc("GET /v1/sync/stock", authMW.RequireAuth(syncHandler.IncrementalSync))
	mux.HandleFunc("GET /v1/search-history", authMW.RequireAuth(syncHandler.GetSearchHistory))
	mux.HandleFunc("POST /v1/search-history", authMW.RequireAuth(syncHandler.CreateSearchHistory))

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           loggingMiddleware(mux),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go func() {
		log.Printf("[INFO] Stock Backend API listening on :%s (PublicBaseURL=%s, DevBypassFirebase=%v)",
			cfg.Port, cfg.PublicBaseURL, cfg.DevBypassFirebase)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("[FATAL] HTTP server error: %v", err)
		}
	}()

	<-ctx.Done()
	log.Println("[INFO] Shutting down Stock Backend API server...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("[WARN] Graceful shutdown error: %v", err)
	}
	log.Println("[INFO] Server stopped cleanly.")
}

func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s (%s)", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
	})
}
