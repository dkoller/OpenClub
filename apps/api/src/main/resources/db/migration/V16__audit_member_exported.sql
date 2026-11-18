-- Exporting one member's stored data for a verified access request is itself audited (privacy proposal
-- section 5). Widens the event-type list; no row changes.
ALTER TABLE audit_events DROP CONSTRAINT audit_events_event_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN (
    'MEMBER_CREATED', 'MEMBER_UPDATED', 'MEMBER_ARCHIVED', 'ROLE_CHANGED',
    'GRANT_ADDED', 'GRANT_REVOKED', 'ADMIN_RECOVERED', 'CLUB_BOOTSTRAPPED', 'MEMBER_ERASED', 'MEMBER_EXPORTED'
));
