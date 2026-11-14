-- R05 login handshakes only; NOT authenticated browser sessions.
-- Store digests of state and browser binding, never their raw values.
CREATE TABLE oidc_login_transactions (
    state_hash CHAR(64) PRIMARY KEY CHECK (state_hash ~ '^[a-f0-9]{64}$'),
    browser_hash CHAR(64) NOT NULL CHECK (browser_hash ~ '^[a-f0-9]{64}$'),
    nonce TEXT NOT NULL CHECK (length(nonce) BETWEEN 43 AND 128),
    code_verifier TEXT NOT NULL CHECK (length(code_verifier) BETWEEN 43 AND 128),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX oidc_login_transactions_expiry_idx ON oidc_login_transactions (expires_at);
-- code_verifier and nonce are confidential until consumed. Restrict API DB role,
-- prevent query parameter logging, and use short TTL / periodic expiry cleanup.
