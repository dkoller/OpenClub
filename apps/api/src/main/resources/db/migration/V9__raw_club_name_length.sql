-- Align direct SQL club-name writes with the operator bootstrap's 200-char limit.
-- The existing nonblank trimmed-length check remains in force.
ALTER TABLE clubs ADD CONSTRAINT clubs_raw_name_length
    CHECK (length(name) <= 200);
