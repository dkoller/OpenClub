-- Coach messages (OpenAPI 1.31.0, owner-approved 2026-11-25, ADR-0008 addendum 35). A coach with MANAGE_TEAMS for an
-- event's team emails everyone in the event, only those who said yes, those who have not answered, or the squad;
-- the server sends it, so nobody sees an address. Guardians receive it for their minors. Each send is logged without
-- its text (time, audience, number of members) for the limit of 10 per team per day; the log goes with the event.
CREATE TABLE coach_messages (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES team_events (id) ON DELETE CASCADE,
    team_id UUID NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    audience TEXT NOT NULL CHECK (audience IN ('ALL', 'YES', 'NO_ANSWER', 'SQUAD')),
    members INT NOT NULL CHECK (members >= 0),
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX coach_messages_team_sent ON coach_messages (team_id, sent_at);

ALTER TABLE notification_settings ADD COLUMN team_messages BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE email_outbox DROP CONSTRAINT email_outbox_kind_check;
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_kind_check
    CHECK (kind IN ('NEW_EVENTS', 'EVENT_CHANGED', 'EVENT_CANCELLED', 'ANNOUNCEMENT', 'SHIFT_REMINDER', 'HELPERS_WANTED',
                    'ANSWER_REMINDER', 'SQUAD_CALLED', 'TEAM_MESSAGE'));
