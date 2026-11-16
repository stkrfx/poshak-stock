-- =============================================================================
-- STOCK BACKEND POSTGRESQL / SUPABASE SCHEMA (001_schema.sql)
-- =============================================================================

-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. USERS TABLE
CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    mobile      TEXT NOT NULL UNIQUE,
    role        TEXT NOT NULL DEFAULT 'employee'
                CHECK (role IN ('admin', 'employee')),
    status      TEXT NOT NULL DEFAULT 'active'
                CHECK (status IN ('active', 'blocked')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. SESSIONS TABLE
CREATE TABLE IF NOT EXISTS sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash  TEXT NOT NULL UNIQUE,
    device_id           TEXT NOT NULL,
    device_name         TEXT,
    device_type         TEXT,
    ip_address          TEXT,
    user_agent          TEXT,
    last_used_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_active ON sessions(user_id) WHERE revoked_at IS NULL;

-- 3. APP RELEASES TABLE (FOR IN-APP SELF-UPDATE)
CREATE TABLE IF NOT EXISTS app_releases (
    id                           SERIAL PRIMARY KEY,
    version_code                 INTEGER NOT NULL UNIQUE CHECK (version_code > 0),
    version_name                 TEXT NOT NULL,
    apk_download_url             TEXT NOT NULL,
    apk_sha256                   TEXT NOT NULL,
    release_notes                TEXT,
    is_force_update              BOOLEAN NOT NULL DEFAULT FALSE,
    min_supported_version_code   INTEGER NOT NULL CHECK (min_supported_version_code > 0),
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_app_releases_version_code ON app_releases(version_code DESC);

-- 4. DEDICATED AUTOCOMPLETE LOOKUP TABLES (POPULATED BY GO INTEGRATOR)
CREATE TABLE IF NOT EXISTS lookup_articles (
    normalized_value TEXT PRIMARY KEY,
    display_value    TEXT NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_lookup_articles_prefix ON lookup_articles (normalized_value text_pattern_ops);

CREATE TABLE IF NOT EXISTS lookup_brands (
    normalized_value TEXT PRIMARY KEY,
    display_value    TEXT NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_lookup_brands_prefix ON lookup_brands (normalized_value text_pattern_ops);

CREATE TABLE IF NOT EXISTS lookup_names (
    normalized_value TEXT PRIMARY KEY,
    display_value    TEXT NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_lookup_names_prefix ON lookup_names (normalized_value text_pattern_ops);

CREATE TABLE IF NOT EXISTS lookup_parties (
    normalized_value TEXT PRIMARY KEY,
    display_value    TEXT NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_lookup_parties_prefix ON lookup_parties (normalized_value text_pattern_ops);

CREATE TABLE IF NOT EXISTS lookup_colors (
    normalized_value TEXT PRIMARY KEY,
    display_value    TEXT NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_lookup_colors_prefix ON lookup_colors (normalized_value text_pattern_ops);

CREATE TABLE IF NOT EXISTS lookup_sizes (
    normalized_value TEXT PRIMARY KEY,
    display_value    TEXT NOT NULL,
    size_sort_order  INTEGER NOT NULL DEFAULT 999999,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_lookup_sizes_prefix ON lookup_sizes (normalized_value text_pattern_ops);

-- 5. PRODUCTS TABLE (EXTRACTED DIRECTLY FROM HABRON BY GO INTEGRATOR)
CREATE TABLE IF NOT EXISTS products (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_product_key  TEXT NOT NULL UNIQUE,

    name                TEXT NOT NULL,
    brand               TEXT NOT NULL,
    article             TEXT,
    color               TEXT,
    mrp                 INTEGER NOT NULL,
    party               TEXT NOT NULL,
    barcode             TEXT NOT NULL,
    brandcode           TEXT NOT NULL,

    source_updated_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_products_name_ci ON products (LOWER(name));
CREATE INDEX IF NOT EXISTS idx_products_brand_ci ON products (LOWER(brand));
CREATE INDEX IF NOT EXISTS idx_products_article_ci ON products (LOWER(article)) WHERE article IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_products_party_ci ON products (LOWER(party));
CREATE INDEX IF NOT EXISTS idx_products_color_ci ON products (LOWER(color)) WHERE color IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_products_barcode ON products (barcode);
CREATE INDEX IF NOT EXISTS idx_products_brandcode ON products (brandcode);
CREATE INDEX IF NOT EXISTS idx_products_updated_at ON products (updated_at);

-- 6. STOCK TABLE (EXTRACTED DIRECTLY FROM HABRON BY GO INTEGRATOR)
CREATE TABLE IF NOT EXISTS stock (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    size                TEXT NOT NULL,
    size_sort_order     INTEGER NOT NULL DEFAULT 999999,
    quantity            INTEGER NOT NULL DEFAULT 0,
    total_in            INTEGER NOT NULL DEFAULT 0,
    total_out           INTEGER NOT NULL DEFAULT 0,
    age_days            INTEGER,
    source_updated_at   TIMESTAMPTZ,
    last_updated        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (product_id, size)
);

CREATE INDEX IF NOT EXISTS idx_stock_product_id ON stock (product_id);
CREATE INDEX IF NOT EXISTS idx_stock_size_ci ON stock (LOWER(size));
CREATE INDEX IF NOT EXISTS idx_stock_last_updated ON stock (last_updated);

-- 7. DELETION LOG FOR INCREMENTAL CLIENT SYNC
CREATE TABLE IF NOT EXISTS sync_deleted_records (
    id          BIGSERIAL PRIMARY KEY,
    entity_type TEXT NOT NULL CHECK (entity_type IN ('product', 'stock')),
    entity_id   UUID NOT NULL,
    deleted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sync_deleted_at ON sync_deleted_records(deleted_at);

-- 8. SEARCH HISTORY TABLE
CREATE TABLE IF NOT EXISTS search_history (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    query_json  JSONB NOT NULL,
    query_text  TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_search_history_created_at ON search_history(created_at DESC);

-- 9. SYNC METADATA TABLE
CREATE TABLE IF NOT EXISTS sync_metadata (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 10. AUTOMATIC UPDATED_AT TRIGGERS
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_updated_at ON users;
CREATE TRIGGER trg_users_updated_at
BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_products_updated_at ON products;
CREATE TRIGGER trg_products_updated_at
BEFORE UPDATE ON products FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE OR REPLACE FUNCTION set_stock_last_updated()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_updated = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_stock_last_updated ON stock;
CREATE TRIGGER trg_stock_last_updated
BEFORE UPDATE ON stock FOR EACH ROW EXECUTE FUNCTION set_stock_last_updated();