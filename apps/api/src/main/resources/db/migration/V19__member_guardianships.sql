-- Guardians signing in for a minor (docs/security/guardian-design.md; contract approved 2026-11-19,
-- ADR-0008 addendum 8). An editor attests that an active adult member with a login is a guardian of an
-- active minor. A guardianship grants no capability. It ends when an editor removes it, when either member
-- is archived or erased, or when the guardian's login is unlinked (triggers below); reaching the club's
-- adult age ends it for every read and write, which check the age on each request.
CREATE TABLE member_guardianships (
    minor_member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    guardian_member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (minor_member_id, guardian_member_id),
    CHECK (minor_member_id <> guardian_member_id)
);
CREATE INDEX member_guardianships_guardian_idx ON member_guardianships (guardian_member_id);

-- Last line of defence for direct SQL: the services check the same rules, with the actor, in one statement.
CREATE FUNCTION validate_guardianship() RETURNS trigger AS $$
BEGIN
    -- Serialize additions for one minor so two concurrent inserts cannot exceed the limit.
    PERFORM 1 FROM members WHERE id = NEW.minor_member_id FOR UPDATE;
    IF NOT EXISTS (SELECT 1 FROM members m JOIN clubs c ON TRUE
                   WHERE m.id = NEW.minor_member_id AND m.archived_at IS NULL
                     AND m.date_of_birth > (CURRENT_DATE - make_interval(years => c.adult_age_years))::date) THEN
        RAISE EXCEPTION 'A guardianship needs an active minor';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM members m JOIN clubs c ON TRUE
                   WHERE m.id = NEW.guardian_member_id AND m.archived_at IS NULL AND m.account_id IS NOT NULL
                     AND m.date_of_birth <= (CURRENT_DATE - make_interval(years => c.adult_age_years))::date) THEN
        RAISE EXCEPTION 'A guardian must be an active adult member with a login';
    END IF;
    IF (SELECT COUNT(*) FROM member_guardianships WHERE minor_member_id = NEW.minor_member_id) >= 2 THEN
        RAISE EXCEPTION 'A minor has at most two guardians';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER member_guardianships_validate
    BEFORE INSERT ON member_guardianships
    FOR EACH ROW EXECUTE FUNCTION validate_guardianship();

-- Archiving either member, or unlinking the guardian's login, ends their guardianships in the same statement.
CREATE FUNCTION end_guardianships_with_member() RETURNS trigger AS $$
BEGIN
    IF NEW.archived_at IS NOT NULL THEN
        DELETE FROM member_guardianships WHERE minor_member_id = NEW.id OR guardian_member_id = NEW.id;
    ELSIF NEW.account_id IS NULL THEN
        DELETE FROM member_guardianships WHERE guardian_member_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER members_end_guardianships
    AFTER UPDATE OF archived_at, account_id ON members
    FOR EACH ROW EXECUTE FUNCTION end_guardianships_with_member();

-- Guardianship events relate two members (the minor is the target, the guardian the related member).
-- An identifier only, like every other audit column; no foreign key, so the trail outlives both members.
ALTER TABLE audit_events ADD COLUMN related_member_id UUID;

ALTER TABLE audit_events DROP CONSTRAINT audit_events_event_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN (
    'MEMBER_CREATED', 'MEMBER_UPDATED', 'MEMBER_ARCHIVED', 'ROLE_CHANGED',
    'GRANT_ADDED', 'GRANT_REVOKED', 'ADMIN_RECOVERED', 'CLUB_BOOTSTRAPPED', 'MEMBER_ERASED', 'MEMBER_EXPORTED',
    'INVITATION_CREATED', 'INVITATION_ACCEPTED', 'INVITATION_REVOKED', 'ACCOUNT_UNLINKED', 'CONTACT_READ',
    'MEMBER_REACTIVATED', 'GUARDIAN_LINKED', 'GUARDIAN_UNLINKED', 'DEPENDENT_READ', 'DEPENDENT_UPDATED'
));
