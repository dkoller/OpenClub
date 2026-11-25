-- Actual attendance and the J+S export (OpenAPI 1.27.0, owner-approved 2026-11-25, ADR-0008 addendum 31).
--
-- A coach (MANAGE_TEAMS for the team) records, once an event has started, who actually came: team members as
-- participants, members with a team-scoped MANAGE_TEAMS grant as leaders. A record stays as long as its event,
-- also after the member leaves the team, so a season can still be exported for J+S; it goes with the event
-- (retention: events 24 months after they end) and with an erased member. How long to keep it is open in #12.
CREATE TABLE event_presence (
    event_id UUID NOT NULL REFERENCES team_events (id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('PARTICIPANT', 'LEADER')),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, member_id)
);

CREATE INDEX event_presence_member ON event_presence (member_id);

-- The member's J+S person number (PERSONENNUMMER of the national sport database), digits only. Set by member
-- editors; shown to them, to the member in My profile and in the member export, and used only in the J+S file.
ALTER TABLE members ADD COLUMN js_person_number TEXT
    CHECK (js_person_number IS NULL OR js_person_number ~ '^[0-9]{1,12}$');

ALTER TABLE audit_events DROP CONSTRAINT audit_events_event_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN (
    'MEMBER_CREATED', 'MEMBER_UPDATED', 'MEMBER_ARCHIVED', 'ROLE_CHANGED',
    'GRANT_ADDED', 'GRANT_REVOKED', 'ADMIN_RECOVERED', 'CLUB_BOOTSTRAPPED', 'MEMBER_ERASED', 'MEMBER_EXPORTED',
    'INVITATION_CREATED', 'INVITATION_ACCEPTED', 'INVITATION_REVOKED', 'ACCOUNT_UNLINKED', 'CONTACT_READ',
    'MEMBER_REACTIVATED', 'GUARDIAN_LINKED', 'GUARDIAN_UNLINKED', 'DEPENDENT_READ', 'DEPENDENT_UPDATED',
    'SELF_CONTACTS_UPDATED', 'FEE_ASSIGNED', 'FEE_CHANGED', 'FEE_PAID', 'FEE_UNPAID', 'FEE_REMOVED', 'MEMBER_LISTED',
    'PUBLIC_LISTING_CHANGED', 'PAYMENT_DETAILS_CHANGED', 'JS_NUMBER_CHANGED', 'JS_ATTENDANCE_EXPORTED'
));
