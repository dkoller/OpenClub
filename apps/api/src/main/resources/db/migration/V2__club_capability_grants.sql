-- First increment of R01/#76: explicit club-wide capability grants.
-- Team-scoped grants depend on first-release teams (#75) and will be added
-- in a later migration. Never store unvalidated arbitrary team UUIDs.
CREATE TABLE club_capability_grants (
    member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    capability TEXT NOT NULL CHECK (capability IN (
        'VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'MANAGE_PERMISSIONS'
    )),
    PRIMARY KEY (member_id, capability)
);

-- No automatic backfill: an existing ADMIN must not silently acquire
-- grant-delegation rights during migration. New installations seed only the
-- first administrator with MANAGE_PERMISSIONS in the operator bootstrap.
