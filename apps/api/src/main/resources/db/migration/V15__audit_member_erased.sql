-- Erasing an archived member (privacy proposal section 4) leaves an identifier-only audit event: the
-- member UUID stays in the trail for its 12-month retention, the person's data does not.
ALTER TABLE audit_events DROP CONSTRAINT audit_events_event_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN (
    'MEMBER_CREATED', 'MEMBER_UPDATED', 'MEMBER_ARCHIVED', 'ROLE_CHANGED',
    'GRANT_ADDED', 'GRANT_REVOKED', 'ADMIN_RECOVERED', 'CLUB_BOOTSTRAPPED', 'MEMBER_ERASED'
));
