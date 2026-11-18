-- Invitations let an administrator-managed adult member sign in themselves (invitation design, owner-approved
-- 2026-11-18). Only the SHA-256 hash of the 256-bit secret is stored: the secret itself is shown once to the
-- inviting administrator. An invitation is open while it is unused, unrevoked and unexpired; there is at most one
-- unused, unrevoked invitation per member (an expired one is cleaned up when a new one is created).
CREATE TABLE member_invitations (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    secret_hash CHAR(64) NOT NULL UNIQUE CHECK (secret_hash ~ '^[a-f0-9]{64}$'),
    created_by_account_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    accepted_by_account_id UUID,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at),
    CHECK (used_at IS NULL OR revoked_at IS NULL),
    CHECK ((used_at IS NULL) = (accepted_by_account_id IS NULL))
);
CREATE UNIQUE INDEX member_invitations_one_unused_per_member
    ON member_invitations (member_id) WHERE used_at IS NULL AND revoked_at IS NULL;

-- New audit events for the invitation flow, account unlinking and (later) contact reads by team managers.
ALTER TABLE audit_events DROP CONSTRAINT audit_events_event_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN (
    'MEMBER_CREATED', 'MEMBER_UPDATED', 'MEMBER_ARCHIVED', 'ROLE_CHANGED',
    'GRANT_ADDED', 'GRANT_REVOKED', 'ADMIN_RECOVERED', 'CLUB_BOOTSTRAPPED', 'MEMBER_ERASED', 'MEMBER_EXPORTED',
    'INVITATION_CREATED', 'INVITATION_ACCEPTED', 'INVITATION_REVOKED', 'ACCOUNT_UNLINKED', 'CONTACT_READ'
));
