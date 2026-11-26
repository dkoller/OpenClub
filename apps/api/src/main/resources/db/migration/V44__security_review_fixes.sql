-- Fixes from the independent security review of 2026-11-26 (OpenAPI 1.15.0–1.33.0).

-- The coach-message log no longer goes with its event: deleting an event and creating a new one must not reset the
-- limit of 10 messages per team per day. A row without an event is deleted by the retention job after a day.
ALTER TABLE coach_messages ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE coach_messages DROP CONSTRAINT coach_messages_event_id_fkey;
ALTER TABLE coach_messages ADD CONSTRAINT coach_messages_event_id_fkey
    FOREIGN KEY (event_id) REFERENCES team_events (id) ON DELETE SET NULL;

-- A push subscription the push service keeps refusing (or that never answers) is deleted after 10 failed wake-ups
-- in a row; a successful one resets the count.
ALTER TABLE push_subscriptions ADD COLUMN failures INTEGER NOT NULL DEFAULT 0 CHECK (failures >= 0);

-- A message's text is kept only as long as it is needed: a recipient without an email address (push only) gets no
-- text stored, and a sent message keeps only its subject (for the push inbox) until it is deleted after 7 days.
ALTER TABLE email_outbox DROP CONSTRAINT email_outbox_body_check;
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_body_check CHECK (length(body) <= 20000);
UPDATE email_outbox SET body = '' WHERE sent_at IS NOT NULL;
