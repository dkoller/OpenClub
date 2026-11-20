-- Carpooling (OpenAPI 1.21.0, owner-approved 2026-11-20, ADR-0008 addendum 25). A signed-in adult of the event's
-- team, or a guardian of a current minor in it, offers one ride per event; team members (guardians for their
-- minors) book seats. Driver and booked passengers see each other's phone (owner decision). Rides go with their
-- event and are deleted 12 months after it ended (retention job).
CREATE TABLE event_rides (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES team_events (id) ON DELETE CASCADE,
    driver_member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    direction TEXT NOT NULL CHECK (direction IN ('THERE', 'BACK', 'BOTH')),
    seats INTEGER NOT NULL CHECK (seats BETWEEN 1 AND 8),
    meeting_point TEXT NOT NULL CHECK (length(meeting_point) BETWEEN 1 AND 200 AND meeting_point = trim(meeting_point)),
    departs_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, driver_member_id)
);
CREATE INDEX event_rides_driver ON event_rides (driver_member_id);

CREATE TABLE ride_passengers (
    ride_id UUID NOT NULL REFERENCES event_rides (id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    -- Who booked: the passenger or their guardian; their phone is the one the driver sees.
    booked_by UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    booked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (ride_id, member_id)
);
CREATE INDEX ride_passengers_member ON ride_passengers (member_id);
CREATE INDEX ride_passengers_booked_by ON ride_passengers (booked_by);

-- Leaving a team (also by archiving) ends one's bookings and own ride offers for that team's events.
CREATE FUNCTION end_rides_with_assignment() RETURNS trigger AS $$
BEGIN
    DELETE FROM ride_passengers p USING event_rides r JOIN team_events e ON e.id = r.event_id
     WHERE p.ride_id = r.id AND e.team_id = OLD.team_id AND p.member_id = OLD.member_id;
    DELETE FROM event_rides r USING team_events e
     WHERE e.id = r.event_id AND e.team_id = OLD.team_id AND r.driver_member_id = OLD.member_id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER member_team_assignments_end_rides
    AFTER DELETE ON member_team_assignments
    FOR EACH ROW EXECUTE FUNCTION end_rides_with_assignment();
