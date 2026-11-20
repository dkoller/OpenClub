-- Private calendar feeds (OpenAPI 1.17.0, owner-approved 2026-11-20, ADR-0008 addendum 21). One feed link per
-- member; only the SHA-256 of its secret is stored. The link works while the member is active with a current
-- verified identity; creating a new link replaces the old one, revoking deletes it, erasure cascades.
CREATE TABLE calendar_feeds (
    member_id UUID PRIMARY KEY REFERENCES members (id) ON DELETE CASCADE,
    secret_hash TEXT NOT NULL UNIQUE CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Archiving a member or changing (or removing) their login link ends the feed for good, so a later reactivation or
-- a new link never revives an old feed link.
CREATE FUNCTION end_calendar_feed() RETURNS trigger AS $$
BEGIN
    IF (NEW.archived_at IS NOT NULL AND OLD.archived_at IS NULL) OR NEW.account_id IS DISTINCT FROM OLD.account_id THEN
        DELETE FROM calendar_feeds WHERE member_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER members_end_calendar_feed
    AFTER UPDATE OF archived_at, account_id ON members
    FOR EACH ROW EXECUTE FUNCTION end_calendar_feed();
