-- Fresh pre-production schema: one club per independently managed installation.
-- No cross-club person directory or multi-tenant membership model.
CREATE TABLE clubs (
    id UUID PRIMARY KEY,
    singleton BOOLEAN NOT NULL DEFAULT TRUE CHECK (singleton),
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 200),
    slug TEXT NOT NULL UNIQUE CHECK (length(slug) BETWEEN 3 AND 60 AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    gender_required_for_players BOOLEAN NOT NULL DEFAULT FALSE,
    adult_age_years INTEGER NOT NULL DEFAULT 18 CHECK (adult_age_years BETWEEN 13 AND 25),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (singleton)
);

-- OIDC accounts are installation-local and are not member profiles.
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    display_name TEXT NOT NULL DEFAULT 'Unlinked OpenClub account',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE account_identities (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    issuer TEXT NOT NULL CHECK (length(trim(issuer)) > 0),
    subject TEXT NOT NULL CHECK (length(trim(subject)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (issuer, subject)
);
CREATE INDEX account_identities_account_idx ON account_identities(account_id);

-- One local member may exist before an account is linked by verified invitation.
CREATE TABLE members (
    id UUID PRIMARY KEY,
    account_id UUID UNIQUE REFERENCES accounts(id) ON DELETE SET NULL,
    first_name TEXT NOT NULL CHECK (length(trim(first_name)) BETWEEN 1 AND 200),
    last_name TEXT NOT NULL CHECK (length(trim(last_name)) BETWEEN 1 AND 200),
    date_of_birth DATE NOT NULL CHECK (date_of_birth > DATE '1850-01-01'),
    email TEXT,
    phone TEXT,
    gender TEXT,
    is_player BOOLEAN NOT NULL DEFAULT FALSE,
    role TEXT NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'MEMBER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (email IS NULL OR length(trim(email)) BETWEEN 3 AND 320),
    CHECK (phone IS NULL OR length(trim(phone)) BETWEEN 1 AND 40)
);
CREATE INDEX members_account_idx ON members(account_id);

-- Authentication security tables remain distinct from local member records.
CREATE TABLE oidc_login_transactions (
    state_hash CHAR(64) PRIMARY KEY CHECK (state_hash ~ '^[a-f0-9]{64}$'),
    browser_hash CHAR(64) NOT NULL CHECK (browser_hash ~ '^[a-f0-9]{64}$'),
    nonce TEXT NOT NULL CHECK (length(nonce) BETWEEN 43 AND 128),
    code_verifier TEXT NOT NULL CHECK (length(code_verifier) BETWEEN 43 AND 128),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX oidc_login_transactions_expiry_idx ON oidc_login_transactions(expires_at);

CREATE TABLE auth_sessions (
    session_hash CHAR(64) PRIMARY KEY CHECK (session_hash ~ '^[a-f0-9]{64}$'),
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    csrf_token VARCHAR(43) NOT NULL CHECK (csrf_token ~ '^[A-Za-z0-9_-]{43}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idle_expires_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_sessions_absolute_lifetime CHECK (absolute_expires_at > created_at),
    CONSTRAINT auth_sessions_idle_lifetime CHECK (idle_expires_at <= absolute_expires_at)
);
CREATE INDEX auth_sessions_account_idx ON auth_sessions(account_id);
CREATE INDEX auth_sessions_expiration_idx ON auth_sessions(idle_expires_at, absolute_expires_at);

-- Enforce member field rules during writes; adulthood transitions need R03 follow-up.
CREATE FUNCTION validate_member_fields() RETURNS trigger AS $$
DECLARE policy RECORD;
BEGIN
    SELECT adult_age_years, gender_required_for_players INTO STRICT policy
      FROM clubs;
    IF NEW.date_of_birth > CURRENT_DATE THEN
        RAISE EXCEPTION 'Birth date cannot be in the future';
    END IF;
    IF NEW.date_of_birth <= (CURRENT_DATE - make_interval(years => policy.adult_age_years))::date
       AND (NULLIF(trim(NEW.email), '') IS NULL OR NULLIF(trim(NEW.phone), '') IS NULL) THEN
        RAISE EXCEPTION 'Adult member requires both email and phone';
    END IF;
    IF NEW.is_player AND policy.gender_required_for_players
       AND NULLIF(trim(NEW.gender), '') IS NULL THEN
        RAISE EXCEPTION 'Player gender required by club policy';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER members_validate BEFORE INSERT OR UPDATE ON members
    FOR EACH ROW EXECUTE FUNCTION validate_member_fields();
