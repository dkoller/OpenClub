-- Join requests (OpenAPI 1.22.0, owner-approved 2026-11-20, ADR-0008 addendum 26). The public form is off until a
-- holder of EDIT_MEMBERS switches it on. An adult applies for themselves (SELF), a parent for a child (CHILD: the
-- child's name and birth date, the parent's name and contacts). No IP address is stored. Open requests are deleted
-- after 60 days (retention job); accepting creates the member and deletes the request; declining deletes it.
ALTER TABLE clubs ADD COLUMN join_requests_open BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE join_requests (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('SELF', 'CHILD')),
    first_name TEXT NOT NULL CHECK (length(first_name) BETWEEN 1 AND 200),
    last_name TEXT NOT NULL CHECK (length(last_name) BETWEEN 1 AND 200),
    date_of_birth DATE NOT NULL,
    email TEXT NOT NULL CHECK (length(email) BETWEEN 3 AND 320),
    phone TEXT NOT NULL CHECK (length(phone) BETWEEN 1 AND 40),
    parent_first_name TEXT CHECK (length(parent_first_name) BETWEEN 1 AND 200),
    parent_last_name TEXT CHECK (length(parent_last_name) BETWEEN 1 AND 200),
    team_id UUID REFERENCES teams (id) ON DELETE SET NULL,
    message TEXT CHECK (length(message) BETWEEN 1 AND 500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((kind = 'SELF') = (parent_first_name IS NULL AND parent_last_name IS NULL)),
    CHECK (kind = 'SELF' OR (parent_first_name IS NOT NULL AND parent_last_name IS NOT NULL))
);
-- The same person (contact address, first name and birth date) has at most one open request.
CREATE UNIQUE INDEX join_requests_person ON join_requests (lower(email), lower(first_name), date_of_birth);
CREATE INDEX join_requests_created ON join_requests (created_at);
