-- Serialize member writes with club policy updates so neither transaction
-- can validate against a policy version that changes before it commits.
CREATE OR REPLACE FUNCTION validate_member_fields() RETURNS trigger AS $$
DECLARE policy RECORD;
BEGIN
    SELECT adult_age_years, gender_required_for_players INTO STRICT policy
      FROM clubs FOR SHARE;
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

-- Reject a stricter club policy until all existing local members comply.
CREATE FUNCTION validate_club_policy_change() RETURNS trigger AS $$
BEGIN
    IF NEW.adult_age_years < OLD.adult_age_years AND EXISTS (
        SELECT 1 FROM members m
        WHERE m.date_of_birth <= (CURRENT_DATE - make_interval(years => NEW.adult_age_years))::date
          AND (NULLIF(trim(m.email), '') IS NULL OR NULLIF(trim(m.phone), '') IS NULL)
    ) THEN
        RAISE EXCEPTION 'Existing members lack required adult contacts';
    END IF;
    IF NEW.gender_required_for_players AND NOT OLD.gender_required_for_players
       AND EXISTS (
           SELECT 1 FROM members m
           WHERE m.is_player AND NULLIF(trim(m.gender), '') IS NULL
       ) THEN
        RAISE EXCEPTION 'Existing players lack required gender';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER clubs_validate_member_policy
    BEFORE UPDATE OF adult_age_years, gender_required_for_players ON clubs
    FOR EACH ROW EXECUTE FUNCTION validate_club_policy_change();
