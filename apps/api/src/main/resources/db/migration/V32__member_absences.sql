-- Absences (OpenAPI 1.20.0, owner-approved 2026-11-20, ADR-0008 addendum 24). A member, or a guardian for their
-- current minor, enters calendar days (club time zone) when the member is away. No reason is stored. For every event
-- of the member's teams starting on such a day without an explicit answer, the answer counts as NO; an explicit
-- answer always wins. Deleted 12 months after the last day (retention job) or with the member (cascade).
CREATE TABLE member_absences (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (to_date >= from_date AND to_date <= from_date + 365)
);
CREATE INDEX member_absences_member ON member_absences (member_id, to_date);

-- The club's time zone for calendar days: the API sets openclub.time_zone on each connection from OPENCLUB_TIME_ZONE.
CREATE FUNCTION club_time_zone() RETURNS TEXT AS $$
    SELECT COALESCE(NULLIF(current_setting('openclub.time_zone', true), ''), 'Europe/Zurich')
$$ LANGUAGE sql STABLE;

-- Explicit answers plus the NO implied by an absence. Every attendance read uses this view.
CREATE VIEW effective_attendance AS
SELECT ea.event_id, ea.member_id, ea.response
FROM event_attendance ea
UNION ALL
SELECT e.id, a.member_id, 'NO'
FROM team_events e
JOIN member_team_assignments a ON a.team_id = e.team_id
WHERE EXISTS (
        SELECT 1 FROM member_absences ab
        WHERE ab.member_id = a.member_id
          AND (e.starts_at AT TIME ZONE club_time_zone())::date BETWEEN ab.from_date AND ab.to_date)
  AND NOT EXISTS (SELECT 1 FROM event_attendance ea WHERE ea.event_id = e.id AND ea.member_id = a.member_id);
