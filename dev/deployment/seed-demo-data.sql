-- Fictional demo data for the hosted SYNTHETIC M1 demo (docs/deployment/infomaniak-demo.md). Every name is
-- invented, every email uses example.invalid and every phone number is a +41 000 placeholder. Run once, in one
-- transaction, right after the first-club bootstrap: it refuses any database that is not a freshly
-- bootstrapped club (exactly one club, exactly one member who is the linked administrator, no teams).
-- Rows are written directly, so the audit trail shows no events for them; that is intended for seed data.
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM clubs) <> 1
       OR (SELECT COUNT(*) FROM members) <> 1
       OR (SELECT COUNT(*) FROM members WHERE role = 'ADMIN' AND account_id IS NOT NULL) <> 1
       OR (SELECT COUNT(*) FROM teams) <> 0 THEN
        RAISE EXCEPTION 'Refusing to seed: expected a freshly bootstrapped club (one club, only its administrator, no teams)';
    END IF;
END $$;

CREATE TEMP TABLE seed_members (first_name text, last_name text, birth date, email text, phone text,
                                gender text, is_player boolean, team text) ON COMMIT DROP;
-- Ages are relative to today, so minors stay minors and adults stay adults whenever the seed runs.
INSERT INTO seed_members VALUES
    ('Lea',     'Brunner',   current_date - interval '11 years 2 months', NULL, NULL,                       'female', true,  'Juniors U12'),
    ('Noah',    'Keller',    current_date - interval '10 years 7 months', NULL, NULL,                       'male',   true,  'Juniors U12'),
    ('Mia',     'Steiner',   current_date - interval '11 years 9 months', NULL, '+41000000101',             'female', true,  'Juniors U12'),
    ('Luca',    'Frei',      current_date - interval '10 years 1 month',  NULL, NULL,                       'male',   true,  'Juniors U12'),
    ('Elena',   'Huber',     current_date - interval '16 years 4 months', 'elena.huber@example.invalid', NULL, 'female', true, 'Juniors U17'),
    ('Jonas',   'Meier',     current_date - interval '15 years 8 months', NULL, '+41000000102',             'male',   true,  'Juniors U17'),
    ('Sofia',   'Bianchi',   current_date - interval '16 years 11 months', NULL, NULL,                      'female', true,  'Juniors U17'),
    ('Anna',    'Schmid',    current_date - interval '27 years 3 months', 'anna.schmid@example.invalid',   '+41000000103', 'female', true, 'Women 1'),
    ('Chiara',  'Rossi',     current_date - interval '24 years 6 months', 'chiara.rossi@example.invalid',  '+41000000104', 'female', true, 'Women 1'),
    ('Julie',   'Dubois',    current_date - interval '31 years 1 month',  'julie.dubois@example.invalid',  '+41000000105', 'female', true, 'Women 1'),
    ('Marco',   'Weber',     current_date - interval '29 years 5 months', 'marco.weber@example.invalid',   '+41000000106', 'male',   true, 'Men 1'),
    ('Thomas',  'Müller',    current_date - interval '33 years 10 months', 'thomas.mueller@example.invalid', '+41000000107', 'male',  true, 'Men 1'),
    ('Luc',     'Favre',     current_date - interval '26 years 2 months', 'luc.favre@example.invalid',     '+41000000108', 'male',   true, 'Men 1'),
    ('Peter',   'Zürcher',   current_date - interval '58 years 7 months', 'peter.zuercher@example.invalid', '+41000000109', NULL,    false, 'Juniors U12'),
    ('Sandra',  'Gerber',    current_date - interval '44 years 9 months', 'sandra.gerber@example.invalid', '+41000000110', NULL,     false, 'Juniors U17'),
    ('Giulia',  'Moretti',   current_date - interval '67 years 4 months', 'giulia.moretti@example.invalid', '+41000000111', NULL,    false, NULL);

INSERT INTO teams (id, name)
SELECT gen_random_uuid(), name FROM (VALUES ('Juniors U12'), ('Juniors U17'), ('Women 1'), ('Men 1')) AS t(name);

WITH created AS (
    INSERT INTO members (id, first_name, last_name, date_of_birth, email, phone, gender, is_player)
    SELECT gen_random_uuid(), first_name, last_name, birth, email, phone, gender, is_player FROM seed_members
    RETURNING id, first_name, last_name
)
INSERT INTO member_team_assignments (team_id, member_id)
SELECT t.id, c.id FROM created c
JOIN seed_members s ON s.first_name = c.first_name AND s.last_name = c.last_name
JOIN teams t ON t.name = s.team;

SELECT (SELECT COUNT(*) FROM teams) AS teams, (SELECT COUNT(*) FROM members) AS members,
       (SELECT COUNT(*) FROM member_team_assignments) AS assignments;
