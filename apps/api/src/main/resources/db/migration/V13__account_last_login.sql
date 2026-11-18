-- Retention (privacy proposal section 3, owner-approved default): an account that never became a member
-- and has not signed in for 90 days is deleted. Sessions are pruned after 12 hours, so the last login has
-- to be recorded on the account itself. Existing accounts start at the migration time, never earlier.
ALTER TABLE accounts ADD COLUMN last_login_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX accounts_last_login_idx ON accounts (last_login_at);
