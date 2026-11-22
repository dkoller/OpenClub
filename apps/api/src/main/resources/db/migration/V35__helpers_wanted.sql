-- Helpers wanted (OpenAPI 1.24.0, owner-approved 2026-11-22, ADR-0008 addendum 28). Seven days before a helper shift
-- that still has free places, the team and the guardians of its minors get one email per event listing those shifts
-- (counts only, never names). Members switch it off with their own setting, on by default. Each shift is called once:
-- the row goes with the shift.
ALTER TABLE notification_settings ADD COLUMN helpers_wanted BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE helper_calls_sent (
    shift_id UUID PRIMARY KEY REFERENCES event_shifts (id) ON DELETE CASCADE,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE email_outbox DROP CONSTRAINT email_outbox_kind_check;
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_kind_check
    CHECK (kind IN ('NEW_EVENTS', 'EVENT_CHANGED', 'EVENT_CANCELLED', 'ANNOUNCEMENT', 'SHIFT_REMINDER', 'HELPERS_WANTED'));
