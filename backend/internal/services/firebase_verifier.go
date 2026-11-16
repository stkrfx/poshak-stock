package services

import (
	"context"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const googleSecureTokenCertsURL = "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"

var maxAgeRegex = regexp.MustCompile(`max-age=(\d+)`)

// FirebaseTokenClaims represents the standard JWT claims inside a Firebase Phone Auth ID Token.
type FirebaseTokenClaims struct {
	PhoneNumber string `json:"phone_number"`
	jwt.RegisteredClaims
}

// FirebaseVerifier validates Firebase Auth ID tokens using Google's SecureToken X.509 public keys
// (and supports an explicit DevBypass mode when DEV_BYPASS_FIREBASE=true for local development).
type FirebaseVerifier struct {
	projectID  string
	devBypass  bool
	httpClient *http.Client

	mu        sync.RWMutex
	keys      map[string]*rsa.PublicKey
	expiresAt time.Time
}

// NewFirebaseVerifier initializes the verifier from the service account file (or FIREBASE_PROJECT_ID).
func NewFirebaseVerifier(credsFile string, devBypass bool) (*FirebaseVerifier, error) {
	projectID := strings.TrimSpace(os.Getenv("FIREBASE_PROJECT_ID"))

	if credsFile != "" {
		raw, err := os.ReadFile(credsFile)
		if err != nil {
			if !devBypass {
				return nil, fmt.Errorf("failed to read FIREBASE_CREDENTIALS_FILE (%s): %w", credsFile, err)
			}
		} else {
			var sa struct {
				ProjectID string `json:"project_id"`
			}
			if err := json.Unmarshal(raw, &sa); err == nil && strings.TrimSpace(sa.ProjectID) != "" {
				projectID = strings.TrimSpace(sa.ProjectID)
			}
		}
	}

	if projectID == "" && !devBypass {
		return nil, errors.New("firebase project_id could not be determined: set FIREBASE_CREDENTIALS_FILE or FIREBASE_PROJECT_ID (or DEV_BYPASS_FIREBASE=true for local dev)")
	}

	return &FirebaseVerifier{
		projectID: projectID,
		devBypass: devBypass,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
		keys: make(map[string]*rsa.PublicKey),
	}, nil
}

// VerifyPhoneToken validates a Firebase ID token and returns the verified E.164 phone number.
func (v *FirebaseVerifier) VerifyPhoneToken(ctx context.Context, idToken string) (string, error) {
	idToken = strings.TrimSpace(idToken)
	if idToken == "" {
		return "", errors.New("empty firebase_id_token")
	}

	// Explicit DevBypass mode for local Hoppscotch testing when DEV_BYPASS_FIREBASE=true:
	// Accepts tokens formatted as "dev_phone:+919876543210" or "dev:+919876543210" or unsigned/test JWTs containing phone_number.
	if v.devBypass {
		if strings.HasPrefix(idToken, "dev_phone:") {
			phone := strings.TrimSpace(strings.TrimPrefix(idToken, "dev_phone:"))
			if phone != "" {
				return phone, nil
			}
		}
		if strings.HasPrefix(idToken, "dev:") {
			phone := strings.TrimSpace(strings.TrimPrefix(idToken, "dev:"))
			if phone != "" {
				return phone, nil
			}
		}
		var unverifiedClaims FirebaseTokenClaims
		parser := jwt.NewParser()
		if _, _, err := parser.ParseUnverified(idToken, &unverifiedClaims); err == nil && unverifiedClaims.PhoneNumber != "" {
			return unverifiedClaims.PhoneNumber, nil
		}
		if v.projectID == "" {
			return "", errors.New("invalid dev firebase_id_token: use 'dev_phone:+91XXXXXXXXXX' in DEV_BYPASS_FIREBASE mode")
		}
	}

	claims := &FirebaseTokenClaims{}
	token, err := jwt.ParseWithClaims(idToken, claims, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodRSA); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		kid, _ := t.Header["kid"].(string)
		if kid == "" {
			return nil, errors.New("missing kid header in firebase token")
		}
		return v.getPublicKey(ctx, kid)
	},
		jwt.WithAudience(v.projectID),
		jwt.WithIssuer("https://securetoken.google.com/"+v.projectID),
		jwt.WithExpirationRequired(),
	)
	if err != nil || !token.Valid {
		return "", fmt.Errorf("invalid firebase_id_token: %w", err)
	}

	if strings.TrimSpace(claims.Subject) == "" {
		return "", errors.New("firebase token subject (sub) is empty")
	}
	if strings.TrimSpace(claims.PhoneNumber) == "" {
		return "", errors.New("firebase token does not contain a verified phone_number claim")
	}

	return strings.TrimSpace(claims.PhoneNumber), nil
}

func (v *FirebaseVerifier) getPublicKey(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	now := time.Now()

	v.mu.RLock()
	if now.Before(v.expiresAt) {
		if pub, ok := v.keys[kid]; ok {
			v.mu.RUnlock()
			return pub, nil
		}
	}
	v.mu.RUnlock()

	if err := v.refreshKeys(ctx); err != nil {
		return nil, err
	}

	v.mu.RLock()
	defer v.mu.RUnlock()
	pub, ok := v.keys[kid]
	if !ok {
		return nil, fmt.Errorf("unknown firebase public key kid: %s", kid)
	}
	return pub, nil
}

func (v *FirebaseVerifier) refreshKeys(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, googleSecureTokenCertsURL, nil)
	if err != nil {
		return err
	}

	resp, err := v.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to fetch google securetoken certs: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status %d fetching google securetoken certs", resp.StatusCode)
	}

	var certMap map[string]string
	if err := json.NewDecoder(resp.Body).Decode(&certMap); err != nil {
		return fmt.Errorf("failed to decode google certs JSON: %w", err)
	}

	parsedKeys := make(map[string]*rsa.PublicKey, len(certMap))
	for k, pemStr := range certMap {
		block, _ := pem.Decode([]byte(pemStr))
		if block == nil {
			continue
		}
		cert, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			continue
		}
		if rsaPub, ok := cert.PublicKey.(*rsa.PublicKey); ok {
			parsedKeys[k] = rsaPub
		}
	}

	ttl := 1 * time.Hour
	if cc := resp.Header.Get("Cache-Control"); cc != "" {
		if matches := maxAgeRegex.FindStringSubmatch(cc); len(matches) == 2 {
			if sec, err := strconv.Atoi(matches[1]); err == nil && sec > 60 {
				ttl = time.Duration(sec) * time.Second
			}
		}
	}

	v.mu.Lock()
	v.keys = parsedKeys
	v.expiresAt = time.Now().Add(ttl)
	v.mu.Unlock()
	return nil
}
