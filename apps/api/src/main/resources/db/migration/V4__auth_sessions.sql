-- R05/D1: opaque authenticated sessions, distinct from OIDC login transactions.
-- The browser receives a 256-bit random handle; only its SHA-256 digest is stored.
CREATE TABLE auth_sessions (
    session_hash CHAR(64) PRIMARY KEY CHECK (session_hash ~ '^[a-f0-9]{64}$'),
    person_id UUID NOT NULL REFERENCES persons(id) ON DELETE CASCADE,
    -- Server-only CSRF secret is returned to JavaScript by a future authenticated
    -- same-origin /auth/me route; it must never be exposed in logs or URLs.
    csrf_token VARCHAR(43) NOT NULL CHECK (csrf_token ~ '^[A-Za-z0-9_-]{43}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idle_expires_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_sessions_absolute_lifetime CHECK (absolute_expires_at > created_at),
    CONSTRAINT auth_sessions_idle_lifetime CHECK (idle_expires_at <= absolute_expires_at)
);
CREATE INDEX auth_sessions_person_idx ON auth_sessions (person_id);
CREATE INDEX auth_sessions_expiration_idx ON auth_sessions (idle_expires_at, absolute_expires_at);
-- Application sessions do not encode club roles. R06 checks membership per club/request.
