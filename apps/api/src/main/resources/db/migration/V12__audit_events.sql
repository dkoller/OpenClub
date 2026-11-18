-- Append-only audit trail for changes to members, roles and grants (#12/#26, privacy proposal section 5).
-- It deliberately stores no names, birth dates, contacts or free text: only who acted, on which member
-- UUID, what kind of change, and for grants which capability and scope. There are no foreign keys, so
-- the trail outlives deleted accounts, members and teams; the UUIDs are pseudonymous identifiers.
CREATE TABLE audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_type TEXT NOT NULL CHECK (event_type IN (
        'MEMBER_CREATED', 'MEMBER_UPDATED', 'MEMBER_ARCHIVED', 'ROLE_CHANGED',
        'GRANT_ADDED', 'GRANT_REVOKED', 'ADMIN_RECOVERED'
    )),
    -- NULL for operator commands, which have no browser-session account.
    actor_account_id UUID,
    target_member_id UUID,
    capability TEXT CHECK (capability IS NULL OR capability IN (
        'VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'MANAGE_PERMISSIONS', 'VIEW_TEAMS', 'MANAGE_TEAMS'
    )),
    scope TEXT CHECK (scope IS NULL OR scope IN ('CLUB', 'TEAM')),
    team_id UUID,
    new_role TEXT CHECK (new_role IS NULL OR new_role IN ('ADMIN', 'MEMBER')),
    -- Grant events carry their capability and scope; role events their new role; nothing else carries extras.
    CHECK ((event_type IN ('GRANT_ADDED', 'GRANT_REVOKED')) = (capability IS NOT NULL AND scope IS NOT NULL)),
    CHECK ((event_type = 'ROLE_CHANGED') = (new_role IS NOT NULL)),
    CHECK ((scope = 'TEAM') = (team_id IS NOT NULL)),
    CHECK (target_member_id IS NOT NULL OR event_type = 'ADMIN_RECOVERED')
);
CREATE INDEX audit_events_occurred_idx ON audit_events (occurred_at);
CREATE INDEX audit_events_target_idx ON audit_events (target_member_id, occurred_at);

-- Rows may be deleted by the retention job but never rewritten.
CREATE FUNCTION audit_events_reject_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events rows are append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_reject_update();
