-- Email notifications (OpenAPI 1.19.0, owner-approved 2026-11-20, ADR-0008 addendum 23). Every kind is on unless
-- the member turns it off; a missing row means all on and German. Mail goes only to active members with a current
-- verified sign-in and an email address, through the SMTP server the operator configured (nothing is queued
-- without one).
CREATE TABLE notification_settings (
    member_id UUID PRIMARY KEY REFERENCES members (id) ON DELETE CASCADE,
    event_changes BOOLEAN NOT NULL DEFAULT TRUE,
    new_events BOOLEAN NOT NULL DEFAULT TRUE,
    announcements BOOLEAN NOT NULL DEFAULT TRUE,
    shift_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    language TEXT NOT NULL DEFAULT 'de' CHECK (language IN ('de', 'en', 'fr', 'it')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The outbox: one rendered message per recipient. The address is looked up when sending, so a changed or removed
-- address, archiving or erasure (cascade) is honoured. Sent rows go after 7 days, undeliverable ones after 2 days.
CREATE TABLE email_outbox (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('NEW_EVENTS', 'EVENT_CHANGED', 'EVENT_CANCELLED', 'ANNOUNCEMENT', 'SHIFT_REMINDER')),
    subject TEXT NOT NULL CHECK (length(subject) BETWEEN 1 AND 300 AND subject !~ '[\r\n]'),
    body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 20000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);
CREATE INDEX email_outbox_due ON email_outbox (next_attempt_at) WHERE sent_at IS NULL;
CREATE INDEX email_outbox_member ON email_outbox (member_id);

-- One reminder per sign-up; goes with the shift or the member.
CREATE TABLE shift_reminders_sent (
    shift_id UUID NOT NULL REFERENCES event_shifts (id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    PRIMARY KEY (shift_id, member_id)
);
