package repositories

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stkrfx/stock-backend/internal/models"
)

var (
	ErrUserNotFound    = errors.New("user not found")
	ErrSessionNotFound = errors.New("session not found")
	ErrSessionRevoked  = errors.New("session revoked")
	ErrAccountBlocked  = errors.New("account blocked")
	ErrDeviceMismatch  = errors.New("device_id mismatch")
)

// UserSessionRepository manages persistence for users and device sessions.
type UserSessionRepository struct {
	pool *pgxpool.Pool
}

func NewUserSessionRepository(pool *pgxpool.Pool) *UserSessionRepository {
	return &UserSessionRepository{pool: pool}
}

// GetUserByMobile fetches a user by their normalized mobile number.
func (r *UserSessionRepository) GetUserByMobile(ctx context.Context, mobile string) (*models.User, error) {
	const q = `
		SELECT id, name, mobile, role, status, created_at, updated_at
		FROM users
		WHERE mobile = $1
	`
	var u models.User
	err := r.pool.QueryRow(ctx, q, mobile).Scan(
		&u.ID, &u.Name, &u.Mobile, &u.Role, &u.Status, &u.CreatedAt, &u.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}

// GetUserByID fetches a user by UUID.
func (r *UserSessionRepository) GetUserByID(ctx context.Context, userID string) (*models.User, error) {
	const q = `
		SELECT id, name, mobile, role, status, created_at, updated_at
		FROM users
		WHERE id = $1
	`
	var u models.User
	err := r.pool.QueryRow(ctx, q, userID).Scan(
		&u.ID, &u.Name, &u.Mobile, &u.Role, &u.Status, &u.CreatedAt, &u.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}

// CreateUser inserts a new user (used by POST /v1/admin/users).
func (r *UserSessionRepository) CreateUser(ctx context.Context, name, mobile, role string) (*models.User, error) {
	const q = `
		INSERT INTO users (name, mobile, role, status)
		VALUES ($1, $2, $3, 'active')
		RETURNING id, name, mobile, role, status, created_at, updated_at
	`
	var u models.User
	err := r.pool.QueryRow(ctx, q, name, mobile, role).Scan(
		&u.ID, &u.Name, &u.Mobile, &u.Role, &u.Status, &u.CreatedAt, &u.UpdatedAt,
	)
	if err != nil {
		return nil, err
	}
	return &u, nil
}

// ListUsers returns all users with their active session count for GET /v1/admin/users.
func (r *UserSessionRepository) ListUsers(ctx context.Context) ([]models.AdminUserView, error) {
	const q = `
		SELECT
			u.id,
			u.name,
			u.mobile,
			u.role,
			u.status,
			COUNT(s.id) FILTER (WHERE s.revoked_at IS NULL) AS active_session_count,
			u.created_at,
			u.updated_at
		FROM users u
		LEFT JOIN sessions s ON s.user_id = u.id
		GROUP BY u.id
		ORDER BY u.created_at DESC
	`
	rows, err := r.pool.Query(ctx, q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var result []models.AdminUserView
	for rows.Next() {
		var item models.AdminUserView
		if err := rows.Scan(
			&item.ID,
			&item.Name,
			&item.Mobile,
			&item.Role,
			&item.Status,
			&item.ActiveSessionCount,
			&item.CreatedAt,
			&item.UpdatedAt,
		); err != nil {
			return nil, err
		}
		result = append(result, item)
	}
	if result == nil {
		result = []models.AdminUserView{}
	}
	return result, rows.Err()
}

// UpdateUserStatus updates a user's status ('active' or 'blocked').
// If status == 'blocked', it atomically revokes all active sessions for that user in the same transaction.
func (r *UserSessionRepository) UpdateUserStatus(ctx context.Context, userID, status string) (*models.User, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	const updateUserQ = `
		UPDATE users
		SET status = $2
		WHERE id = $1
		RETURNING id, name, mobile, role, status, created_at, updated_at
	`
	var u models.User
	err = tx.QueryRow(ctx, updateUserQ, userID, status).Scan(
		&u.ID, &u.Name, &u.Mobile, &u.Role, &u.Status, &u.CreatedAt, &u.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, err
	}

	if status == models.StatusBlocked {
		const revokeQ = `
			UPDATE sessions
			SET revoked_at = NOW()
			WHERE user_id = $1 AND revoked_at IS NULL
		`
		if _, err := tx.Exec(ctx, revokeQ, userID); err != nil {
			return nil, fmt.Errorf("failed to revoke user sessions: %w", err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return &u, nil
}

// UpdateUserRole updates a user's role ('admin' or 'employee').
func (r *UserSessionRepository) UpdateUserRole(ctx context.Context, userID, role string) (*models.User, error) {
	const q = `
		UPDATE users
		SET role = $2
		WHERE id = $1
		RETURNING id, name, mobile, role, status, created_at, updated_at
	`
	var u models.User
	err := r.pool.QueryRow(ctx, q, userID, role).Scan(
		&u.ID, &u.Name, &u.Mobile, &u.Role, &u.Status, &u.CreatedAt, &u.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}

// CreateSession creates a new session row upon login.
func (r *UserSessionRepository) CreateSession(
	ctx context.Context,
	userID, refreshTokenHash, deviceID, deviceName, deviceType, ipAddress, userAgent string,
) (*models.Session, error) {
	const q = `
		INSERT INTO sessions (
			user_id, refresh_token_hash, device_id, device_name, device_type, ip_address, user_agent
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		RETURNING id, user_id, device_id, device_name, device_type, ip_address, user_agent, last_used_at, created_at, revoked_at
	`
	var s models.Session
	err := r.pool.QueryRow(
		ctx, q, userID, refreshTokenHash, deviceID, deviceName, deviceType, ipAddress, userAgent,
	).Scan(
		&s.ID, &s.UserID, &s.DeviceID, &s.DeviceName, &s.DeviceType, &s.IPAddress, &s.UserAgent, &s.LastUsedAt, &s.CreatedAt, &s.RevokedAt,
	)
	if err != nil {
		return nil, err
	}
	return &s, nil
}

// ValidateSessionAndUser checks whether a session is unrevoked and the owning user is active.
// Returns the current user role, user status, and whether the session is revoked.
func (r *UserSessionRepository) ValidateSessionAndUser(
	ctx context.Context, sessionID string,
) (userID string, role string, userStatus string, sessionRevoked bool, err error) {
	const q = `
		SELECT u.id, u.role, u.status, (s.revoked_at IS NOT NULL) AS is_revoked
		FROM sessions s
		JOIN users u ON u.id = s.user_id
		WHERE s.id = $1
	`
	err = r.pool.QueryRow(ctx, q, sessionID).Scan(&userID, &role, &userStatus, &sessionRevoked)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", "", "", true, ErrSessionNotFound
	}
	return userID, role, userStatus, sessionRevoked, err
}

// RotateRefreshToken atomically locks the session row (SELECT ... FOR UPDATE),
// verifies the session is active, user is active, and device_id matches,
// and rotates the refresh_token_hash.
func (r *UserSessionRepository) RotateRefreshToken(
	ctx context.Context,
	oldTokenHash string,
	newTokenHash string,
	deviceID string,
	ipAddress string,
	userAgent string,
) (*models.User, string, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, "", err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	const selectForUpdateQ = `
		SELECT
			s.id,
			s.device_id,
			s.revoked_at,
			u.id,
			u.name,
			u.mobile,
			u.role,
			u.status,
			u.created_at,
			u.updated_at
		FROM sessions s
		JOIN users u ON u.id = s.user_id
		WHERE s.refresh_token_hash = $1
		FOR UPDATE OF s
	`

	var (
		sessionID       string
		storedDeviceID  string
		revokedAt       *time.Time
		user            models.User
	)

	err = tx.QueryRow(ctx, selectForUpdateQ, oldTokenHash).Scan(
		&sessionID,
		&storedDeviceID,
		&revokedAt,
		&user.ID,
		&user.Name,
		&user.Mobile,
		&user.Role,
		&user.Status,
		&user.CreatedAt,
		&user.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, "", ErrSessionRevoked
	}
	if err != nil {
		return nil, "", err
	}

	if user.Status != models.StatusActive {
		return nil, "", ErrAccountBlocked
	}
	if revokedAt != nil {
		return nil, "", ErrSessionRevoked
	}
	if storedDeviceID != deviceID {
		return nil, "", ErrDeviceMismatch
	}

	const updateSessionQ = `
		UPDATE sessions
		SET refresh_token_hash = $2,
		    last_used_at = NOW(),
		    ip_address = $3,
		    user_agent = $4
		WHERE id = $1
	`
	if _, err := tx.Exec(ctx, updateSessionQ, sessionID, newTokenHash, ipAddress, userAgent); err != nil {
		return nil, "", err
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, "", err
	}
	return &user, sessionID, nil
}

// RevokeSessionByTokenHash marks a session as revoked on user logout.
func (r *UserSessionRepository) RevokeSessionByTokenHash(ctx context.Context, tokenHash string) error {
	const q = `
		UPDATE sessions
		SET revoked_at = NOW()
		WHERE refresh_token_hash = $1 AND revoked_at IS NULL
	`
	_, err := r.pool.Exec(ctx, q, tokenHash)
	return err
}

// RevokeSessionByID immediately revokes a specific device session (DELETE /v1/admin/sessions/{session_id}).
func (r *UserSessionRepository) RevokeSessionByID(ctx context.Context, sessionID string) (string, error) {
	const q = `
		UPDATE sessions
		SET revoked_at = COALESCE(revoked_at, NOW())
		WHERE id = $1
		RETURNING user_id
	`
	var userID string
	err := r.pool.QueryRow(ctx, q, sessionID).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrSessionNotFound
	}
	return userID, err
}

// ListUserSessions returns all sessions for a specific user (GET /v1/admin/users/{id}/sessions).
func (r *UserSessionRepository) ListUserSessions(ctx context.Context, userID string) ([]models.Session, error) {
	const q = `
		SELECT
			id,
			user_id,
			device_id,
			COALESCE(device_name, ''),
			COALESCE(device_type, ''),
			COALESCE(ip_address, ''),
			COALESCE(user_agent, ''),
			last_used_at,
			created_at,
			revoked_at
		FROM sessions
		WHERE user_id = $1
		ORDER BY last_used_at DESC
	`
	rows, err := r.pool.Query(ctx, q, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var sessions []models.Session
	for rows.Next() {
		var s models.Session
		if err := rows.Scan(
			&s.ID,
			&s.UserID,
			&s.DeviceID,
			&s.DeviceName,
			&s.DeviceType,
			&s.IPAddress,
			&s.UserAgent,
			&s.LastUsedAt,
			&s.CreatedAt,
			&s.RevokedAt,
		); err != nil {
			return nil, err
		}
		sessions = append(sessions, s)
	}
	if sessions == nil {
		sessions = []models.Session{}
	}
	return sessions, rows.Err()
}