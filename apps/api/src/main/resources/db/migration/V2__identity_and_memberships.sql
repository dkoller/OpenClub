-- People are not login accounts: club members can exist before receiving an invitation.
CREATE TABLE persons (
    id UUID PRIMARY KEY,
    display_name TEXT NOT NULL CHECK (length(trim(display_name)) BETWEEN 1 AND 200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Provider-neutral binding: never use email or an unverified claim as a user ID.
-- The (issuer, subject) pair comes from a validated authentication token.
CREATE TABLE person_identities (
    id UUID PRIMARY KEY,
    person_id UUID NOT NULL REFERENCES persons(id) ON DELETE CASCADE,
    issuer TEXT NOT NULL CHECK (length(trim(issuer)) > 0),
    subject TEXT NOT NULL CHECK (length(trim(subject)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT person_identities_issuer_subject_unique UNIQUE (issuer, subject)
);
CREATE INDEX person_identities_person_id_idx ON person_identities(person_id);

-- A short club identifier is required for future URL and API contracts.
-- Backfill clubs created before this migration with unique, stable IDs.
ALTER TABLE clubs ADD COLUMN slug TEXT;
UPDATE clubs SET slug = 'club-' || replace(id::text, '-', '') WHERE slug IS NULL;
ALTER TABLE clubs ALTER COLUMN slug SET NOT NULL;
ALTER TABLE clubs ADD CONSTRAINT clubs_slug_unique UNIQUE (slug);
ALTER TABLE clubs ADD CONSTRAINT clubs_slug_format CHECK (
    length(slug) BETWEEN 3 AND 60 AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
);

-- The membership, including permissions, belongs to a club, not the person globally.
CREATE TABLE memberships (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    person_id UUID NOT NULL REFERENCES persons(id),
    role TEXT NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'MEMBER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT memberships_club_person_unique UNIQUE (club_id, person_id)
);
CREATE INDEX memberships_person_id_idx ON memberships(person_id);
-- Always filter reads and writes by the authenticated actor's authorized club.
-- These FK/unique constraints are NOT an authorization system or RLS policy.
