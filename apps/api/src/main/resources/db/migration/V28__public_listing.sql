-- Public member list opt-out (OpenAPI 1.15.0, controller decision C5/C14b of 2026-11-20, ADR-0008 addendum 19).
-- Every member stays on the anonymous public list unless they (or, for anyone, an editor at their request) switch it
-- off. The switch is a member change: it advances row_version (V21 trigger) and is audited as PUBLIC_LISTING_CHANGED.
ALTER TABLE members ADD COLUMN listed_publicly BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE audit_events DROP CONSTRAINT audit_events_event_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN (
    'MEMBER_CREATED', 'MEMBER_UPDATED', 'MEMBER_ARCHIVED', 'ROLE_CHANGED',
    'GRANT_ADDED', 'GRANT_REVOKED', 'ADMIN_RECOVERED', 'CLUB_BOOTSTRAPPED', 'MEMBER_ERASED', 'MEMBER_EXPORTED',
    'INVITATION_CREATED', 'INVITATION_ACCEPTED', 'INVITATION_REVOKED', 'ACCOUNT_UNLINKED', 'CONTACT_READ',
    'MEMBER_REACTIVATED', 'GUARDIAN_LINKED', 'GUARDIAN_UNLINKED', 'DEPENDENT_READ', 'DEPENDENT_UPDATED',
    'SELF_CONTACTS_UPDATED', 'FEE_ASSIGNED', 'FEE_CHANGED', 'FEE_PAID', 'FEE_UNPAID', 'FEE_REMOVED', 'MEMBER_LISTED',
    'PUBLIC_LISTING_CHANGED'
));
