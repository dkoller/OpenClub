-- Fields and halls (OpenAPI 1.30.0, owner-approved 2026-11-25, ADR-0008 addendum 34). The club keeps a list of the
-- places it books (club content, no personal data), managed with the new club-wide capability MANAGE_VENUES, which
-- the first administrator gets at bootstrap. An event may name one; two events that are not cancelled can never
-- hold the same venue at overlapping times. Deleting a venue keeps its events, without the venue.
ALTER TABLE club_capability_grants DROP CONSTRAINT club_capability_grants_capability_check;
ALTER TABLE club_capability_grants ADD CONSTRAINT club_capability_grants_capability_check CHECK (capability IN (
    'VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'MANAGE_PERMISSIONS', 'VIEW_TEAMS', 'MANAGE_TEAMS', 'MANAGE_FEES',
    'POST_ANNOUNCEMENTS', 'MANAGE_VENUES'));

ALTER TABLE audit_events DROP CONSTRAINT audit_events_capability_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_capability_check CHECK (capability IS NULL OR capability IN (
    'VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'MANAGE_PERMISSIONS', 'VIEW_TEAMS', 'MANAGE_TEAMS', 'MANAGE_FEES',
    'POST_ANNOUNCEMENTS', 'MANAGE_VENUES'));

CREATE TABLE venues (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100 AND name = trim(name)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX venues_name_unique ON venues (lower(name));

ALTER TABLE team_events ADD COLUMN venue_id UUID REFERENCES venues (id) ON DELETE SET NULL;

-- No double booking: btree_gist lets the exclusion constraint compare the venue with = and the times with &&.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE team_events ADD CONSTRAINT team_events_no_double_booking
    EXCLUDE USING gist (venue_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
    WHERE (venue_id IS NOT NULL AND NOT cancelled);
