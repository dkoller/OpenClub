-- A grant is confined to a real team and a local member.
-- Only team viewing and assignment management can be delegated at team scope;
-- permission administration and club-wide member editing are never team grants.
CREATE TABLE team_capability_grants (
    team_id UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    capability TEXT NOT NULL CHECK (capability IN ('VIEW_TEAMS', 'MANAGE_TEAMS')),
    PRIMARY KEY (team_id, member_id, capability)
);
CREATE INDEX team_capability_grants_member_idx
    ON team_capability_grants(member_id);
