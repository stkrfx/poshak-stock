package repositories

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stkrfx/stock-backend/internal/models"
)

var ErrReleaseNotFound = errors.New("app release not found")

// ReleaseRepository manages app_releases records for the in-app self-update system.
type ReleaseRepository struct {
	pool *pgxpool.Pool
}

func NewReleaseRepository(pool *pgxpool.Pool) *ReleaseRepository {
	return &ReleaseRepository{pool: pool}
}

// CreateRelease inserts or updates an app release entry in app_releases.
func (r *ReleaseRepository) CreateRelease(
	ctx context.Context,
	versionCode int,
	versionName string,
	apkDownloadURL string,
	apkSha256 string,
	releaseNotes string,
	isForceUpdate bool,
	minSupportedVersionCode int,
) (*models.AppRelease, error) {
	const q = `
		INSERT INTO app_releases (
			version_code,
			version_name,
			apk_download_url,
			apk_sha256,
			release_notes,
			is_force_update,
			min_supported_version_code
		)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (version_code) DO UPDATE SET
			version_name = EXCLUDED.version_name,
			apk_download_url = EXCLUDED.apk_download_url,
			apk_sha256 = EXCLUDED.apk_sha256,
			release_notes = EXCLUDED.release_notes,
			is_force_update = EXCLUDED.is_force_update,
			min_supported_version_code = EXCLUDED.min_supported_version_code
		RETURNING
			id,
			version_code,
			version_name,
			apk_download_url,
			apk_sha256,
			COALESCE(release_notes, ''),
			is_force_update,
			min_supported_version_code,
			created_at
	`
	var rel models.AppRelease
	err := r.pool.QueryRow(
		ctx,
		q,
		versionCode,
		versionName,
		apkDownloadURL,
		apkSha256,
		releaseNotes,
		isForceUpdate,
		minSupportedVersionCode,
	).Scan(
		&rel.ID,
		&rel.VersionCode,
		&rel.VersionName,
		&rel.APKDownloadURL,
		&rel.APKSha256,
		&rel.ReleaseNotes,
		&rel.IsForceUpdate,
		&rel.MinSupportedVersionCode,
		&rel.CreatedAt,
	)
	if err != nil {
		return nil, err
	}
	return &rel, nil
}

// GetLatestRelease retrieves the highest version_code release from app_releases.
func (r *ReleaseRepository) GetLatestRelease(ctx context.Context) (*models.AppRelease, error) {
	const q = `
		SELECT
			id,
			version_code,
			version_name,
			apk_download_url,
			apk_sha256,
			COALESCE(release_notes, ''),
			is_force_update,
			min_supported_version_code,
			created_at
		FROM app_releases
		ORDER BY version_code DESC
		LIMIT 1
	`
	var rel models.AppRelease
	err := r.pool.QueryRow(ctx, q).Scan(
		&rel.ID,
		&rel.VersionCode,
		&rel.VersionName,
		&rel.APKDownloadURL,
		&rel.APKSha256,
		&rel.ReleaseNotes,
		&rel.IsForceUpdate,
		&rel.MinSupportedVersionCode,
		&rel.CreatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrReleaseNotFound
	}
	if err != nil {
		return nil, err
	}
	return &rel, nil
}

// GetReleaseByVersionCode retrieves a specific release by its integer version_code.
func (r *ReleaseRepository) GetReleaseByVersionCode(ctx context.Context, versionCode int) (*models.AppRelease, error) {
	const q = `
		SELECT
			id,
			version_code,
			version_name,
			apk_download_url,
			apk_sha256,
			COALESCE(release_notes, ''),
			is_force_update,
			min_supported_version_code,
			created_at
		FROM app_releases
		WHERE version_code = $1
	`
	var rel models.AppRelease
	err := r.pool.QueryRow(ctx, q, versionCode).Scan(
		&rel.ID,
		&rel.VersionCode,
		&rel.VersionName,
		&rel.APKDownloadURL,
		&rel.APKSha256,
		&rel.ReleaseNotes,
		&rel.IsForceUpdate,
		&rel.MinSupportedVersionCode,
		&rel.CreatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrReleaseNotFound
	}
	if err != nil {
		return nil, err
	}
	return &rel, nil
}
