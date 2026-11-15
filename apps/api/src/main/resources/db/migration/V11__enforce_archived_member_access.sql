-- Fail closed for direct SQL as well as internal service calls: archived
-- members may hold neither access grants nor active team assignments.
CREATE FUNCTION reject_archived_member_reference() RETURNS trigger AS $$
BEGIN
    -- FOR SHARE serializes direct grant/assignment insertions with archival.
    PERFORM 1 FROM members
      WHERE id = NEW.member_id AND archived_at IS NULL FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Cannot assign an archived or missing member';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER club_grants_active_member
    BEFORE INSERT OR UPDATE ON club_capability_grants
    FOR EACH ROW EXECUTE FUNCTION reject_archived_member_reference();
CREATE TRIGGER team_grants_active_member
    BEFORE INSERT OR UPDATE ON team_capability_grants
    FOR EACH ROW EXECUTE FUNCTION reject_archived_member_reference();
CREATE TRIGGER team_assignments_active_member
    BEFORE INSERT OR UPDATE ON member_team_assignments
    FOR EACH ROW EXECUTE FUNCTION reject_archived_member_reference();

-- Directly setting archived_at must not retain old permissions or assignments.
-- The normal service revokes those rows atomically before updating archived_at.
CREATE FUNCTION reject_archive_with_access() RETURNS trigger AS $$
BEGIN
    IF NEW.archived_at IS NOT NULL AND
       (EXISTS (SELECT 1 FROM club_capability_grants WHERE member_id = NEW.id)
        OR EXISTS (SELECT 1 FROM team_capability_grants WHERE member_id = NEW.id)
        OR EXISTS (SELECT 1 FROM member_team_assignments WHERE member_id = NEW.id)) THEN
        RAISE EXCEPTION 'Archived member still has active access or team assignments';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER members_archive_no_access
    BEFORE UPDATE OF archived_at ON members
    FOR EACH ROW EXECUTE FUNCTION reject_archive_with_access();
