-- Notes on answers and absences, and an answer deadline (OpenAPI 1.39.0, owner-approved 2026-11-27, ADR-0008
-- addendum 44). A note is at most 100 characters, trimmed and never empty; it is seen only by the team's coaches and
-- viewers and by whoever wrote it, and goes with its answer or absence. The owner reversed the rule of addendum 24
-- that an absence carries no reason: an absence may now carry a note, shown next to the No it implies.
ALTER TABLE event_attendance ADD COLUMN note TEXT
    CHECK (note IS NULL OR (length(note) BETWEEN 1 AND 100 AND note = trim(note)));
ALTER TABLE member_absences ADD COLUMN note TEXT
    CHECK (note IS NULL OR (length(note) BETWEEN 1 AND 100 AND note = trim(note)));

-- After the deadline members can no longer answer, change or clear an answer. The API keeps it before the start;
-- moving the start before it removes the deadline (see TeamEvents).
ALTER TABLE team_events ADD COLUMN answers_until TIMESTAMPTZ;
ALTER TABLE team_events ADD CONSTRAINT team_events_answers_until_check
    CHECK (answers_until IS NULL OR answers_until < starts_at);

-- The view gains the note (appended, so existing readers keep working): an explicit answer's own note, or for the No
-- implied by an absence the note of the earliest absence covering that day.
CREATE OR REPLACE VIEW effective_attendance AS
SELECT ea.event_id, ea.member_id, ea.response, ea.note
FROM event_attendance ea
UNION ALL
SELECT e.id, a.member_id, 'NO',
       (SELECT ab.note FROM member_absences ab
        WHERE ab.member_id = a.member_id
          AND (e.starts_at AT TIME ZONE club_time_zone())::date BETWEEN ab.from_date AND ab.to_date
        ORDER BY ab.from_date, ab.created_at, ab.id LIMIT 1)
FROM team_events e
JOIN member_team_assignments a ON a.team_id = e.team_id
WHERE EXISTS (
        SELECT 1 FROM member_absences ab
        WHERE ab.member_id = a.member_id
          AND (e.starts_at AT TIME ZONE club_time_zone())::date BETWEEN ab.from_date AND ab.to_date)
  AND NOT EXISTS (SELECT 1 FROM event_attendance ea WHERE ea.event_id = e.id AND ea.member_id = a.member_id);
