-- Attendance (OpenAPI 1.11.0, owner-approved 2026-11-19, ADR-0008 addendum 14). A member, or a guardian for their
-- current minor, answers YES, NO or MAYBE for an event of a team the member is assigned to, until the event starts.
-- No free-text reason, so no health details are stored. An answer goes with the event, with the member, and when the
-- member leaves the team (an archive removes the assignments, so it ends there too). Not audited.
CREATE TABLE event_attendance (
    event_id UUID NOT NULL REFERENCES team_events (id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    response TEXT NOT NULL CHECK (response IN ('YES', 'NO', 'MAYBE')),
    answered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, member_id)
);

CREATE INDEX event_attendance_member ON event_attendance (member_id);

CREATE FUNCTION end_attendance_with_assignment() RETURNS trigger AS $$
BEGIN
    DELETE FROM event_attendance ea
     USING team_events e
     WHERE ea.event_id = e.id AND e.team_id = OLD.team_id AND ea.member_id = OLD.member_id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER member_team_assignments_end_attendance
    AFTER DELETE ON member_team_assignments
    FOR EACH ROW EXECUTE FUNCTION end_attendance_with_assignment();
