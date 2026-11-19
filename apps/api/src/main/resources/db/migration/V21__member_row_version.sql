-- Optimistic concurrency for the full member replacement (OpenAPI 1.8.0, owner-approved 2026-11-19 after the
-- independent security review). Every change to a member row, by any route, command or trigger, advances its
-- version. The editor's detail read returns it as an ETag; PUT /api/v1/members/{id} must send it back in
-- If-Match and is refused with 412 when the row changed in between (for example a guardian's contact update).
ALTER TABLE members ADD COLUMN row_version BIGINT NOT NULL DEFAULT 1;

CREATE FUNCTION advance_member_row_version() RETURNS trigger AS $$
BEGIN
    NEW.row_version := OLD.row_version + 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER members_advance_row_version
    BEFORE UPDATE ON members
    FOR EACH ROW EXECUTE FUNCTION advance_member_row_version();
