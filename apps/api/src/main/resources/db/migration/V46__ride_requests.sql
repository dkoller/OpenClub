-- Ride requests (OpenAPI 1.41.0, owner-approved 2026-11-27, ADR-0008 addendum 44): a member, or a guardian for their
-- current minor, says the person needs a ride to an event of their team: there, back or both. One request per person
-- and event. It is seen by the same people as the rides (by display name, no phone), removed when the person books a
-- seat on a ride covering the direction, and goes with the event, the member or the team assignment.
CREATE TABLE ride_requests (
    event_id UUID NOT NULL REFERENCES team_events (id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    direction TEXT NOT NULL CHECK (direction IN ('THERE', 'BACK', 'BOTH')),
    -- Who asked: the person or their guardian. Kept only to answer "who may withdraw"; never shown.
    requested_by UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, member_id)
);
CREATE INDEX ride_requests_member ON ride_requests (member_id);
CREATE INDEX ride_requests_requested_by ON ride_requests (requested_by);

-- Leaving a team (also by archiving) ends one's requests for that team's events, as for rides (V33).
CREATE FUNCTION end_ride_requests_with_assignment() RETURNS trigger AS $$
BEGIN
    DELETE FROM ride_requests q USING team_events e
     WHERE e.id = q.event_id AND e.team_id = OLD.team_id AND q.member_id = OLD.member_id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER member_team_assignments_end_ride_requests
    AFTER DELETE ON member_team_assignments
    FOR EACH ROW EXECUTE FUNCTION end_ride_requests_with_assignment();
