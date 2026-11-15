-- A team belongs to this installation's one club; no tenant ID is needed.
CREATE TABLE teams (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX teams_name_unique ON teams (lower(name));

-- A member can belong to multiple teams; duplicate assignments are forbidden.
CREATE TABLE member_team_assignments (
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    PRIMARY KEY (team_id, member_id)
);
CREATE INDEX member_team_assignments_member_idx ON member_team_assignments(member_id);

-- Extend the fixed vocabulary; no grant is automatically issued to existing
-- ADMIN users or team members. Explicit manager assignment is required.
ALTER TABLE club_capability_grants
    DROP CONSTRAINT club_capability_grants_capability_check;
ALTER TABLE club_capability_grants
    ADD CONSTRAINT club_capability_grants_capability_check
    CHECK (capability IN (
        'VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'MANAGE_PERMISSIONS',
        'VIEW_TEAMS', 'MANAGE_TEAMS'
    ));
