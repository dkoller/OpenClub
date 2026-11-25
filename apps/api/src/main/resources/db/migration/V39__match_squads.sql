-- Match squads (OpenAPI 1.28.0, owner-approved 2026-11-25, ADR-0008 addendum 32). A coach with MANAGE_TEAMS for the
-- team saves who is called up for a match, with an optional meeting time and meeting point. Members called for the
-- first time (and the guardians of a called minor) get an email unless they switched squad calls off. Members and
-- guardians see only their own status; the full list only the team's managers. The squad goes with its event, and a
-- member leaves it when they leave the team.
CREATE TABLE event_squads (
    event_id UUID PRIMARY KEY REFERENCES team_events (id) ON DELETE CASCADE,
    meet_at TIMESTAMPTZ,
    meeting_point TEXT CHECK (meeting_point IS NULL
        OR (length(meeting_point) BETWEEN 1 AND 200 AND meeting_point = trim(meeting_point))),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE squad_members (
    event_id UUID NOT NULL REFERENCES event_squads (event_id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, member_id)
);

CREATE INDEX squad_members_member ON squad_members (member_id);

CREATE FUNCTION end_squad_with_assignment() RETURNS trigger AS $$
BEGIN
    DELETE FROM squad_members sm
     USING team_events e
     WHERE sm.event_id = e.id AND e.team_id = OLD.team_id AND sm.member_id = OLD.member_id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER member_team_assignments_end_squad
    AFTER DELETE ON member_team_assignments
    FOR EACH ROW EXECUTE FUNCTION end_squad_with_assignment();

ALTER TABLE notification_settings ADD COLUMN squad_calls BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE email_outbox DROP CONSTRAINT email_outbox_kind_check;
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_kind_check
    CHECK (kind IN ('NEW_EVENTS', 'EVENT_CHANGED', 'EVENT_CANCELLED', 'ANNOUNCEMENT', 'SHIFT_REMINDER', 'HELPERS_WANTED',
                    'ANSWER_REMINDER', 'SQUAD_CALLED'));
