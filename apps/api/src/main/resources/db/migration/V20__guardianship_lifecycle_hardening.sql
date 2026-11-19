-- Guardianship lifecycle hardening (independent security review of 2026-11-19, docs/security/guardian-design.md).
-- V19 only hid a guardianship once the minor reached the adult age; the row stayed. A later birth-date
-- correction or a raised adult age then made the minor a minor again and silently revived the guardian's
-- access. A guardianship now ends for good as soon as either member's age, or the club's adult age, makes
-- the pair invalid, and whenever the guardian's login changes (unlink or administrator recovery).

-- Lock the guardian too, so a concurrent unlink either commits first (and the check below sees it) or waits
-- for this insert (and its trigger then deletes the new row).
CREATE OR REPLACE FUNCTION validate_guardianship() RETURNS trigger AS $$
BEGIN
    -- Serialize additions for one minor so two concurrent inserts cannot exceed the limit.
    PERFORM 1 FROM members WHERE id = NEW.minor_member_id FOR UPDATE;
    PERFORM 1 FROM members WHERE id = NEW.guardian_member_id FOR SHARE;
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

-- Archiving either member ends their guardianships; any change of the guardian's login (unlink, or an
-- administrator recovery binding the record to another account) ends theirs; a changed birth date ends every
-- guardianship in which the minor counts as an adult, or the guardian as a minor, under the old or the new date.
CREATE OR REPLACE FUNCTION end_guardianships_with_member() RETURNS trigger AS $$
BEGIN
    IF NEW.archived_at IS NOT NULL THEN
        DELETE FROM member_guardianships WHERE minor_member_id = NEW.id OR guardian_member_id = NEW.id;
        RETURN NEW;
    END IF;
    IF NEW.account_id IS DISTINCT FROM OLD.account_id THEN
        DELETE FROM member_guardianships WHERE guardian_member_id = NEW.id;
    END IF;
    IF NEW.date_of_birth IS DISTINCT FROM OLD.date_of_birth THEN
        DELETE FROM member_guardianships g
         WHERE (g.minor_member_id = NEW.id AND EXISTS (SELECT 1 FROM clubs c
                    WHERE LEAST(OLD.date_of_birth, NEW.date_of_birth)
                          <= (CURRENT_DATE - make_interval(years => c.adult_age_years))::date))
            OR (g.guardian_member_id = NEW.id AND EXISTS (SELECT 1 FROM clubs c
                    WHERE GREATEST(OLD.date_of_birth, NEW.date_of_birth)
                          > (CURRENT_DATE - make_interval(years => c.adult_age_years))::date));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER members_end_guardianships ON members;
CREATE TRIGGER members_end_guardianships
    AFTER UPDATE OF archived_at, account_id, date_of_birth ON members
    FOR EACH ROW EXECUTE FUNCTION end_guardianships_with_member();

-- A changed adult age ends every guardianship whose minor is an adult, or whose guardian is a minor, under the
-- old or the new age. Raising the age therefore never revives a guardianship of someone who had come of age.
CREATE FUNCTION end_guardianships_on_adult_age_change() RETURNS trigger AS $$
BEGIN
    IF NEW.adult_age_years IS DISTINCT FROM OLD.adult_age_years THEN
        DELETE FROM member_guardianships g
         USING members minor, members guardian
         WHERE minor.id = g.minor_member_id AND guardian.id = g.guardian_member_id
           AND (minor.date_of_birth <= (CURRENT_DATE
                    - make_interval(years => LEAST(OLD.adult_age_years, NEW.adult_age_years)))::date
                OR guardian.date_of_birth > (CURRENT_DATE
                    - make_interval(years => GREATEST(OLD.adult_age_years, NEW.adult_age_years)))::date);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER clubs_end_guardianships_on_adult_age_change
    AFTER UPDATE OF adult_age_years ON clubs
    FOR EACH ROW EXECUTE FUNCTION end_guardianships_on_adult_age_change();

-- Remove rows that V19 left behind: minors who have come of age, and guardians without a login.
DELETE FROM member_guardianships g
 USING members minor, members guardian
 WHERE minor.id = g.minor_member_id AND guardian.id = g.guardian_member_id
   AND (guardian.account_id IS NULL
        OR EXISTS (SELECT 1 FROM clubs c
                   WHERE minor.date_of_birth <= (CURRENT_DATE - make_interval(years => c.adult_age_years))::date
                      OR guardian.date_of_birth > (CURRENT_DATE - make_interval(years => c.adult_age_years))::date));
