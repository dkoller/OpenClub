-- Push notifications (OpenAPI 1.33.0, owner-approved 2026-11-25, ADR-0008 addendum 37). A member's browser on a phone
-- or computer registers its push endpoint (a URL of the browser vendor's push service). The server then sends an
-- empty wake-up push for each new message; the app fetches the message text from this server, so no personal data
-- passes through the push service. The same per-kind switches as for email apply. A subscription goes with the
-- member and is deleted when the push service reports it gone.
CREATE TABLE push_subscriptions (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL UNIQUE CHECK (length(endpoint) BETWEEN 12 AND 1000 AND endpoint LIKE 'https://%'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_pushed_at TIMESTAMPTZ
);
CREATE INDEX push_subscriptions_member ON push_subscriptions (member_id);

-- Each queued message wakes the member's devices once.
ALTER TABLE email_outbox ADD COLUMN push_done BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX email_outbox_push_pending ON email_outbox (created_at) WHERE NOT push_done;
