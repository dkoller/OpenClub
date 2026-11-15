-- Team names are created/renamed with trimmed values by the internal service.
-- Preserve uniqueness even for direct SQL writers that include surrounding spaces.
DROP INDEX teams_name_unique;
CREATE UNIQUE INDEX teams_name_unique ON teams (lower(trim(name)));
