package middleware

import (
	"net/http"
	"strings"
)

const corsAllowedMethods = "GET, POST, PATCH, DELETE, OPTIONS"

const corsAllowedHeaders = "Accept, Authorization, Content-Type, X-Admin-API-Key, X-Admin-Secret"

func CORS(allowedOrigins []string) func(http.Handler) http.Handler {
	originSet := make(map[string]struct{}, len(allowedOrigins))

	for _, origin := range allowedOrigins {
		origin = strings.TrimRight(strings.TrimSpace(origin), "/")
		if origin != "" {
			originSet[origin] = struct{}{}
		}
	}

	allowedMethods := map[string]struct{}{
		http.MethodGet:     {},
		http.MethodPost:    {},
		http.MethodPatch:   {},
		http.MethodDelete:  {},
		http.MethodOptions: {},
	}

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := strings.TrimSpace(r.Header.Get("Origin"))

			isPreflight := r.Method == http.MethodOptions &&
				r.Header.Get("Access-Control-Request-Method") != ""

			// Requests without an Origin header are not browser CORS requests.
			if origin == "" {
				next.ServeHTTP(w, r)
				return
			}

			// Only explicitly configured browser origins are allowed.
			if _, allowed := originSet[origin]; !allowed {
				if isPreflight {
					http.Error(w, "CORS origin not allowed", http.StatusForbidden)
					return
				}

				next.ServeHTTP(w, r)
				return
			}

			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Add("Vary", "Origin")

			w.Header().Set("Access-Control-Allow-Methods", corsAllowedMethods)
			w.Header().Set("Access-Control-Allow-Headers", corsAllowedHeaders)
			w.Header().Set("Access-Control-Max-Age", "600")

			// Handle browser preflight before the authenticated route handlers.
			if isPreflight {
				requestedMethod := strings.ToUpper(
					strings.TrimSpace(
						r.Header.Get("Access-Control-Request-Method"),
					),
				)

				if _, allowed := allowedMethods[requestedMethod]; !allowed {
					http.Error(w, "CORS method not allowed", http.StatusMethodNotAllowed)
					return
				}

				w.WriteHeader(http.StatusNoContent)
				return
			}

			next.ServeHTTP(w, r)
		})
	}
}
