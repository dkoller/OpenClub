-- Volunteer shifts (OpenAPI 1.16.0, owner-approved 2026-11-20, ADR-0008 addendum 20). A shift belongs to one team
-- event and goes with it. Team managers (MANAGE_TEAMS for the team) manage shifts; members of the team, and
-- guardians for their current minors, sign up until the shift starts and while places are free. Not audited.
CREATE TABLE event_shifts (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES team_events (id) ON DELETE CASCADE,
    title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 200 AND title = trim(title)),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    needed INTEGER NOT NULL CHECK (needed BETWEEN 1 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at AND ends_at <= starts_at + INTERVAL '24 hours')
);
CREATE INDEX event_shifts_event ON event_shifts (event_id, starts_at);

CREATE TABLE shift_volunteers (
    shift_id UUID NOT NULL REFERENCES event_shifts (id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    signed_up_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shift_id, member_id)
);
CREATE INDEX shift_volunteers_member ON shift_volunteers (member_id);

-- Leaving a team (also by archiving) ends the member's sign-ups for that team's shifts, like their answers (V24).
CREATE FUNCTION end_shifts_with_assignment() RETURNS trigger AS $$
BEGIN
    DELETE FROM shift_volunteers v
     USING event_shifts s JOIN team_events e ON e.id = s.event_id
     WHERE v.shift_id = s.id AND e.team_id = OLD.team_id AND v.member_id = OLD.member_id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER member_team_assignments_end_shifts
    AFTER DELETE ON member_team_assignments
    FOR EACH ROW EXECUTE FUNCTION end_shifts_with_assignment();
