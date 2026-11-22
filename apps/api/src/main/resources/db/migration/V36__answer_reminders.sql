-- Answer reminders (OpenAPI 1.25.0, owner-approved 2026-11-22, ADR-0008 addendum 29). Two days before an event (more
-- than one day ahead), and when a coach with MANAGE_TEAMS asks (at most every 12 hours), members of the team who have
-- not answered (or the guardians of minors who have not) get an email naming the event and, for a guardian, their own
-- child. Members switch it off with their own setting, on by default. Each row records one run; rows go with the event.
ALTER TABLE notification_settings ADD COLUMN answer_reminders BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE answer_reminders (
    event_id UUID NOT NULL REFERENCES team_events (id) ON DELETE CASCADE,
    automatic BOOLEAN NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, automatic, sent_at)
);
-- The automatic reminder runs once per event.
CREATE UNIQUE INDEX answer_reminders_automatic_once ON answer_reminders (event_id) WHERE automatic;

ALTER TABLE email_outbox DROP CONSTRAINT email_outbox_kind_check;
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_kind_check
    CHECK (kind IN ('NEW_EVENTS', 'EVENT_CHANGED', 'EVENT_CANCELLED', 'ANNOUNCEMENT', 'SHIFT_REMINDER', 'HELPERS_WANTED',
                    'ANSWER_REMINDER'));
