-- Trainings and events (OpenAPI 1.10.0, owner-approved 2026-11-19, ADR-0008 addendum 13). An event belongs to one
-- team and holds no personal data: kind, title, time, place, notes and a cancelled flag. Deleting a team deletes its
-- events. Times are absolute (timestamptz); weekly copies are created by the service in the caller's time zone, so a
-- training keeps its local time across daylight-saving changes.
CREATE TABLE team_events (
    id UUID PRIMARY KEY,
    team_id UUID NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('TRAINING', 'MATCH', 'OTHER')),
    title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 200 AND title = trim(title)),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    location TEXT CHECK (location IS NULL OR (length(trim(location)) BETWEEN 1 AND 200 AND location = trim(location))),
    notes TEXT CHECK (notes IS NULL OR (length(trim(notes)) BETWEEN 1 AND 2000 AND notes = trim(notes))),
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at AND ends_at <= starts_at + INTERVAL '7 days'),
    CHECK (starts_at >= TIMESTAMPTZ '2000-01-01 00:00:00+00' AND starts_at < TIMESTAMPTZ '2100-01-01 00:00:00+00')
);

CREATE INDEX team_events_team_starts ON team_events (team_id, starts_at);
