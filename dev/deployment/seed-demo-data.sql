-- Fictional demo data for the hosted SYNTHETIC M1 demo (docs/deployment/infomaniak-demo.md). Every name is
-- invented, every email uses example.invalid and every phone number is a +41 000 placeholder. Run once, in one
-- transaction, right after the first-club bootstrap: it refuses any database that is not a freshly
-- bootstrapped club (exactly one club, exactly one member who is the linked administrator, no teams, no join
-- requests), so a second run stops with an error and changes nothing.
-- Rows are written directly, so the audit trail shows no events for them; that is intended for seed data.
-- Besides teams and members it adds trainings and a match per team for the next three weeks, some answers, one
-- absence, helper shifts and two rides for the U12 match five days ahead, and one open join request. Guardianships
-- need a guardian with a login, so they are left to the walkthrough (Sandra Gerber for Lea and Nico Brunner).
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM clubs) <> 1
       OR (SELECT COUNT(*) FROM members) <> 1
       OR (SELECT COUNT(*) FROM members WHERE role = 'ADMIN' AND account_id IS NOT NULL) <> 1
       OR (SELECT COUNT(*) FROM teams) <> 0
       OR (SELECT COUNT(*) FROM join_requests) <> 0 THEN
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
    ('Nico',    'Brunner',   current_date - interval '15 years 3 months', NULL, NULL,                       'male',   true,  'Juniors U17'),
    ('Anna',    'Schmid',    current_date - interval '27 years 3 months', 'anna.schmid@example.invalid',   '+41000000103', 'female', true, 'Women 1'),
    ('Chiara',  'Rossi',     current_date - interval '24 years 6 months', 'chiara.rossi@example.invalid',  '+41000000104', 'female', true, 'Women 1'),
    ('Julie',   'Dubois',    current_date - interval '31 years 1 month',  'julie.dubois@example.invalid',  '+41000000105', 'female', true, 'Women 1'),
    ('Marco',   'Weber',     current_date - interval '29 years 5 months', 'marco.weber@example.invalid',   '+41000000106', 'male',   true, 'Men 1'),
    ('Thomas',  'Müller',    current_date - interval '33 years 10 months', 'thomas.mueller@example.invalid', '+41000000107', 'male',  true, 'Men 1'),
    ('Luc',     'Favre',     current_date - interval '26 years 2 months', 'luc.favre@example.invalid',     '+41000000108', 'male',   true, 'Men 1'),
    ('Peter',   'Zürcher',   current_date - interval '58 years 7 months', 'peter.zuercher@example.invalid', '+41000000109', NULL,    false, 'Juniors U12'),
    ('Daniel',  'Roth',      current_date - interval '41 years 2 months', 'daniel.roth@example.invalid',   '+41000000112', NULL,     false, 'Juniors U12'),
    ('Martina', 'Wyss',      current_date - interval '38 years 8 months', 'martina.wyss@example.invalid',  '+41000000113', NULL,     false, 'Juniors U12'),
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

-- Trainings and one match per team over the next three weeks: day offsets from today and local club times, so
-- the first U12 and U17 trainings and matches always fall in the next 7 days and the U12 match (shifts, rides) 5 days ahead.
CREATE TEMP TABLE seed_events (key text, team text, kind text, title text, day int, at time, minutes int,
                               location text, id uuid DEFAULT gen_random_uuid()) ON COMMIT DROP;
INSERT INTO seed_events (key, team, kind, title, day, at, minutes, location) VALUES
    ('u12-t1', 'Juniors U12', 'TRAINING', 'Training',                    2, '17:30', 90,  'Musterfeld sports ground'),
    ('u12-m',  'Juniors U12', 'MATCH',    'Home match vs FC Beispieldorf', 5, '10:00', 120, 'Musterfeld sports ground'),
    ('u12-t2', 'Juniors U12', 'TRAINING', 'Training',                    9, '17:30', 90,  'Musterfeld sports ground'),
    ('u12-t3', 'Juniors U12', 'TRAINING', 'Training',                   16, '17:30', 90,  'Musterfeld sports ground'),
    ('u17-t1', 'Juniors U17', 'TRAINING', 'Training',                    1, '18:30', 90,  'Musterfeld sports ground'),
    ('u17-m',  'Juniors U17', 'MATCH',    'Away match at SC Musterhausen', 6, '14:00', 120, 'Musterhausen stadium'),
    ('u17-t2', 'Juniors U17', 'TRAINING', 'Training',                    8, '18:30', 90,  'Musterfeld sports ground'),
    ('u17-t3', 'Juniors U17', 'TRAINING', 'Training',                   15, '18:30', 90,  'Musterfeld sports ground'),
    ('w1-t1',  'Women 1',     'TRAINING', 'Training',                    3, '19:30', 90,  'Musterfeld sports ground'),
    ('w1-t2',  'Women 1',     'TRAINING', 'Training',                   10, '19:30', 90,  'Musterfeld sports ground'),
    ('w1-m',   'Women 1',     'MATCH',    'Home match vs FC Exempla',   12, '16:00', 120, 'Musterfeld sports ground'),
    ('w1-t3',  'Women 1',     'TRAINING', 'Training',                   17, '19:30', 90,  'Musterfeld sports ground'),
    ('m1-t1',  'Men 1',       'TRAINING', 'Training',                    4, '20:00', 90,  'Musterfeld sports ground'),
    ('m1-t2',  'Men 1',       'TRAINING', 'Training',                   11, '20:00', 90,  'Musterfeld sports ground'),
    ('m1-m',   'Men 1',       'MATCH',    'Away match at AC Fittizia',  13, '18:00', 120, 'Fittizia sports park'),
    ('m1-t3',  'Men 1',       'TRAINING', 'Training',                   18, '20:00', 90,  'Musterfeld sports ground');

INSERT INTO team_events (id, team_id, kind, title, starts_at, ends_at, location)
SELECT e.id, t.id, e.kind, e.title, s.starts, s.starts + make_interval(mins => e.minutes), e.location
FROM seed_events e
JOIN teams t ON t.name = e.team
CROSS JOIN LATERAL (SELECT (((now() AT TIME ZONE club_time_zone())::date + e.day) + e.at)
                           AT TIME ZONE club_time_zone() AS starts) s;

CREATE TEMP VIEW seed_member_ids AS
SELECT m.id, m.first_name || ' ' || m.last_name AS name FROM members m WHERE m.role = 'MEMBER';

-- Answers by team members; everyone not listed has no answer yet (open). Lea answers the U12 match Yes and not the
-- training; Nico answers the U17 training Yes and not the match.
INSERT INTO event_attendance (event_id, member_id, response)
SELECT e.id, m.id, a.response
FROM (VALUES
    ('u12-t1', 'Noah Keller', 'YES'), ('u12-t1', 'Mia Steiner', 'NO'), ('u12-t1', 'Luca Frei', 'YES'),
    ('u12-t1', 'Martina Wyss', 'MAYBE'),
    ('u12-m', 'Lea Brunner', 'YES'), ('u12-m', 'Noah Keller', 'YES'), ('u12-m', 'Mia Steiner', 'YES'),
    ('u12-m', 'Luca Frei', 'YES'), ('u12-m', 'Daniel Roth', 'YES'), ('u12-m', 'Martina Wyss', 'YES'),
    ('u12-t2', 'Noah Keller', 'YES'),
    ('u17-t1', 'Nico Brunner', 'YES'), ('u17-t1', 'Elena Huber', 'YES'), ('u17-t1', 'Jonas Meier', 'NO'),
    ('u17-m', 'Elena Huber', 'YES'), ('u17-m', 'Sofia Bianchi', 'YES'), ('u17-m', 'Jonas Meier', 'MAYBE'),
    ('w1-t1', 'Anna Schmid', 'YES'), ('w1-t1', 'Chiara Rossi', 'NO'), ('w1-m', 'Anna Schmid', 'YES'),
    ('w1-m', 'Julie Dubois', 'YES'),
    ('m1-t1', 'Marco Weber', 'YES'), ('m1-t1', 'Thomas Müller', 'YES'), ('m1-t1', 'Luc Favre', 'NO'),
    ('m1-m', 'Luc Favre', 'YES')) AS a(event, member, response)
JOIN seed_events e ON e.key = a.event
JOIN seed_member_ids m ON m.name = a.member;

-- Luca is away on the day of the second U12 training only, so it counts as No for him there.
INSERT INTO member_absences (id, member_id, from_date, to_date)
SELECT gen_random_uuid(), m.id, d.day, d.day
FROM seed_member_ids m
CROSS JOIN (SELECT (now() AT TIME ZONE club_time_zone())::date + day AS day FROM seed_events WHERE key = 'u12-t2') d
WHERE m.name = 'Luca Frei';

-- Helper shifts at the U12 home match: the kiosk is full, the cake stall has two of three places free.
CREATE TEMP TABLE seed_shifts (title text, before_start interval, needed int, id uuid DEFAULT gen_random_uuid())
    ON COMMIT DROP;
INSERT INTO seed_shifts (title, before_start, needed) VALUES ('Kiosk', '30 minutes', 2), ('Cake stall', '60 minutes', 3);
INSERT INTO event_shifts (id, event_id, title, starts_at, ends_at, needed)
SELECT s.id, e.id, s.title, e.starts_at - s.before_start, e.ends_at, s.needed
FROM seed_shifts s CROSS JOIN team_events e JOIN seed_events k ON k.id = e.id AND k.key = 'u12-m';
INSERT INTO shift_volunteers (shift_id, member_id)
SELECT s.id, m.id
FROM (VALUES ('Kiosk', 'Daniel Roth'), ('Kiosk', 'Martina Wyss'), ('Cake stall', 'Mia Steiner')) AS v(shift, member)
JOIN seed_shifts s ON s.title = v.shift
JOIN seed_member_ids m ON m.name = v.member;

-- Two rides to the U12 match by adult U12 members: Daniel's has two of three seats free, Martina's is full. Lea is
-- booked on neither, so once Sandra is her guardian, Start offers Daniel's ride.
CREATE TEMP TABLE seed_rides (driver text, direction text, seats int, meeting_point text, before_start interval,
                              id uuid DEFAULT gen_random_uuid()) ON COMMIT DROP;
INSERT INTO seed_rides (driver, direction, seats, meeting_point, before_start) VALUES
    ('Daniel Roth',  'BOTH',  3, 'Musterdorf railway station', '40 minutes'),
    ('Martina Wyss', 'THERE', 2, 'Musterdorf school car park', '30 minutes');
INSERT INTO event_rides (id, event_id, driver_member_id, direction, seats, meeting_point, departs_at)
SELECT r.id, e.id, m.id, r.direction, r.seats, r.meeting_point, e.starts_at - r.before_start
FROM seed_rides r
JOIN seed_member_ids m ON m.name = r.driver
CROSS JOIN team_events e JOIN seed_events k ON k.id = e.id AND k.key = 'u12-m';
-- The children booked their own seats (no guardian with a login yet).
INSERT INTO ride_passengers (ride_id, member_id, booked_by)
SELECT r.id, m.id, m.id
FROM (VALUES ('Daniel Roth', 'Luca Frei'), ('Martina Wyss', 'Noah Keller'), ('Martina Wyss', 'Mia Steiner'))
     AS p(driver, passenger)
JOIN seed_rides r ON r.driver = p.driver
JOIN seed_member_ids m ON m.name = p.passenger;

-- One open join request: a fictional parent applies for a child, so the administration has something waiting.
INSERT INTO join_requests (id, kind, first_name, last_name, date_of_birth, email, phone, parent_first_name,
                           parent_last_name, team_id, message, created_at)
SELECT gen_random_uuid(), 'CHILD', 'Mila', 'Baumann', current_date - interval '9 years 4 months',
       'patrick.baumann@example.invalid', '+41000000120', 'Patrick', 'Baumann', t.id,
       'Mila would like to try a training first.', now() - interval '2 days'
FROM teams t WHERE t.name = 'Juniors U12';

SELECT (SELECT COUNT(*) FROM teams) AS teams, (SELECT COUNT(*) FROM members) AS members,
       (SELECT COUNT(*) FROM member_team_assignments) AS assignments, (SELECT COUNT(*) FROM team_events) AS events,
       (SELECT COUNT(*) FROM event_attendance) AS answers, (SELECT COUNT(*) FROM event_shifts) AS shifts,
       (SELECT COUNT(*) FROM event_rides) AS rides, (SELECT COUNT(*) FROM join_requests) AS join_requests;
