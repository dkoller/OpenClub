-- Announcements (OpenAPI 1.14.0, owner-approved 2026-11-19, ADR-0008 addendum 18). Club content, not member data:
-- a title, plain text and a "show until" date, for the whole club (posted with the new club-wide capability
-- POST_ANNOUNCEMENTS) or one team (posted with MANAGE_TEAMS for that team). Deleting a team deletes its announcements.
ALTER TABLE club_capability_grants DROP CONSTRAINT club_capability_grants_capability_check;
ALTER TABLE club_capability_grants ADD CONSTRAINT club_capability_grants_capability_check CHECK (capability IN (
    'VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'MANAGE_PERMISSIONS', 'VIEW_TEAMS', 'MANAGE_TEAMS', 'MANAGE_FEES',
    'POST_ANNOUNCEMENTS'));

ALTER TABLE audit_events DROP CONSTRAINT audit_events_capability_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_capability_check CHECK (capability IS NULL OR capability IN (
    'VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'MANAGE_PERMISSIONS', 'VIEW_TEAMS', 'MANAGE_TEAMS', 'MANAGE_FEES',
    'POST_ANNOUNCEMENTS'));

CREATE TABLE announcements (
    id UUID PRIMARY KEY,
    team_id UUID REFERENCES teams (id) ON DELETE CASCADE,
    title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 200 AND title = trim(title)),
    body TEXT NOT NULL CHECK (length(trim(body)) BETWEEN 1 AND 5000 AND body = trim(body)),
    show_until DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX announcements_team_until ON announcements (team_id, show_until);
