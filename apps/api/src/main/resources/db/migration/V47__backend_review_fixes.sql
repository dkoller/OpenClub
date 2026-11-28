-- Backend review of 2026-11-28. Behaviour does not change.

-- 1. Leaving a team (also by archiving the member) used to fire five separate triggers from V24, V29, V33, V39 and
--    V46. One function now lists everything that goes with a team assignment, in one place:
--    answers, helper shifts, rides offered and seats booked, call-ups and ride requests for that team's events.
CREATE FUNCTION end_team_assignment() RETURNS trigger AS $$
BEGIN
    DELETE FROM event_attendance ea USING team_events e
     WHERE ea.event_id = e.id AND e.team_id = OLD.team_id AND ea.member_id = OLD.member_id;
    DELETE FROM shift_volunteers v USING event_shifts s JOIN team_events e ON e.id = s.event_id
     WHERE v.shift_id = s.id AND e.team_id = OLD.team_id AND v.member_id = OLD.member_id;
    DELETE FROM ride_passengers p USING event_rides r JOIN team_events e ON e.id = r.event_id
     WHERE p.ride_id = r.id AND e.team_id = OLD.team_id AND p.member_id = OLD.member_id;
    DELETE FROM event_rides r USING team_events e
     WHERE e.id = r.event_id AND e.team_id = OLD.team_id AND r.driver_member_id = OLD.member_id;
    DELETE FROM squad_members sm USING team_events e
     WHERE sm.event_id = e.id AND e.team_id = OLD.team_id AND sm.member_id = OLD.member_id;
    DELETE FROM ride_requests q USING team_events e
     WHERE q.event_id = e.id AND e.team_id = OLD.team_id AND q.member_id = OLD.member_id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER member_team_assignments_end_attendance ON member_team_assignments;
DROP TRIGGER member_team_assignments_end_shifts ON member_team_assignments;
DROP TRIGGER member_team_assignments_end_rides ON member_team_assignments;
DROP TRIGGER member_team_assignments_end_squad ON member_team_assignments;
DROP TRIGGER member_team_assignments_end_ride_requests ON member_team_assignments;
DROP FUNCTION end_attendance_with_assignment();
DROP FUNCTION end_shifts_with_assignment();
DROP FUNCTION end_rides_with_assignment();
DROP FUNCTION end_squad_with_assignment();
DROP FUNCTION end_ride_requests_with_assignment();

CREATE TRIGGER member_team_assignments_end
    AFTER DELETE ON member_team_assignments
    FOR EACH ROW EXECUTE FUNCTION end_team_assignment();

-- 2. Deleting an event no longer scans every coach message for its event.
CREATE INDEX coach_messages_event ON coach_messages (event_id);

-- 3. The No implied by an absence looks up the covering absence once (it used to look twice: whether one exists, then
--    its note). Same rows, same columns.
CREATE OR REPLACE VIEW effective_attendance AS
SELECT ea.event_id, ea.member_id, ea.response, ea.note
FROM event_attendance ea
UNION ALL
SELECT e.id, a.member_id, 'NO', ab.note
FROM team_events e
JOIN member_team_assignments a ON a.team_id = e.team_id
JOIN LATERAL (
    SELECT ab.note FROM member_absences ab
    WHERE ab.member_id = a.member_id
      AND (e.starts_at AT TIME ZONE club_time_zone())::date BETWEEN ab.from_date AND ab.to_date
    ORDER BY ab.from_date, ab.created_at, ab.id
    LIMIT 1
) ab ON TRUE
WHERE NOT EXISTS (SELECT 1 FROM event_attendance ea WHERE ea.event_id = e.id AND ea.member_id = a.member_id);
