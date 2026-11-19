-- Membership fees (OpenAPI 1.12.0, owner-approved 2026-11-19, ADR-0008 addendum 15). Tracking only: no payment
-- processing, no bank data. A holder of the new club-wide MANAGE_FEES capability creates a fee period (name, due date,
-- amount in Swiss cents) that includes every active member, changes one member's amount, excludes or re-includes a
-- member and marks payments by hand. Every change to a member's fee is audited with identifiers only.
ALTER TABLE club_capability_grants DROP CONSTRAINT club_capability_grants_capability_check;
ALTER TABLE club_capability_grants ADD CONSTRAINT club_capability_grants_capability_check CHECK (capability IN (
    'VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'MANAGE_PERMISSIONS', 'VIEW_TEAMS', 'MANAGE_TEAMS', 'MANAGE_FEES'));

ALTER TABLE audit_events DROP CONSTRAINT audit_events_capability_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_capability_check CHECK (capability IS NULL OR capability IN (
    'VIEW_MEMBER_DIRECTORY', 'EDIT_MEMBERS', 'MANAGE_PERMISSIONS', 'VIEW_TEAMS', 'MANAGE_TEAMS', 'MANAGE_FEES'));

ALTER TABLE audit_events DROP CONSTRAINT audit_events_event_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN (
    'MEMBER_CREATED', 'MEMBER_UPDATED', 'MEMBER_ARCHIVED', 'ROLE_CHANGED',
    'GRANT_ADDED', 'GRANT_REVOKED', 'ADMIN_RECOVERED', 'CLUB_BOOTSTRAPPED', 'MEMBER_ERASED', 'MEMBER_EXPORTED',
    'INVITATION_CREATED', 'INVITATION_ACCEPTED', 'INVITATION_REVOKED', 'ACCOUNT_UNLINKED', 'CONTACT_READ',
    'MEMBER_REACTIVATED', 'GUARDIAN_LINKED', 'GUARDIAN_UNLINKED', 'DEPENDENT_READ', 'DEPENDENT_UPDATED',
    'SELF_CONTACTS_UPDATED', 'FEE_ASSIGNED', 'FEE_CHANGED', 'FEE_PAID', 'FEE_UNPAID', 'FEE_REMOVED'
));

CREATE TABLE fee_periods (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 200 AND name = trim(name)),
    due_date DATE NOT NULL CHECK (due_date BETWEEN DATE '2000-01-01' AND DATE '2099-12-31'),
    amount_cents INTEGER NOT NULL CHECK (amount_cents BETWEEN 0 AND 10000000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX fee_periods_name_unique ON fee_periods (lower(name));

-- One row per member in a period; a missing row means the member is excluded. Paid on a date, or open (NULL).
CREATE TABLE member_fees (
    period_id UUID NOT NULL REFERENCES fee_periods (id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    amount_cents INTEGER NOT NULL CHECK (amount_cents BETWEEN 0 AND 10000000),
    paid_on DATE CHECK (paid_on IS NULL OR paid_on BETWEEN DATE '2000-01-01' AND DATE '2099-12-31'),
    PRIMARY KEY (period_id, member_id)
);
CREATE INDEX member_fees_member ON member_fees (member_id);
