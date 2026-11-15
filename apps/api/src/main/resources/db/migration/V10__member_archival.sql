-- Archived records remain installation-local for retention decisions, but lose
-- active membership and must not retain a linked login or ADMIN role.
ALTER TABLE members ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE members ADD CONSTRAINT members_archived_not_linked_or_admin
    CHECK (archived_at IS NULL OR (account_id IS NULL AND role = 'MEMBER'));
CREATE INDEX members_active_name_idx
    ON members (last_name, first_name, id) WHERE archived_at IS NULL;
