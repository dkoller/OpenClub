-- Helper duty (OpenAPI 1.32.0, owner-approved 2026-11-25, ADR-0008 addendum 36). The club may ask each adult member
-- of a team, and each guardian of a minor in a team, for a number of helper shifts per season (0 = off). A shift
-- counts once it has started and its event was not cancelled, for the volunteer and for the guardians of a minor
-- volunteer. Set by MANAGE_FEES holders, who see the overview (the substitute fee stays a manual fee).
ALTER TABLE clubs ADD COLUMN helper_quota INTEGER NOT NULL DEFAULT 0 CHECK (helper_quota BETWEEN 0 AND 50);
ALTER TABLE clubs ADD COLUMN season_start_month INTEGER NOT NULL DEFAULT 8 CHECK (season_start_month BETWEEN 1 AND 12);
