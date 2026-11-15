-- Direct SQL must not bypass application maximum lengths with trailing spaces.
-- The existing trimmed/nonblank checks remain in force.
ALTER TABLE members ADD CONSTRAINT members_raw_field_lengths CHECK (
    length(first_name) <= 200 AND length(last_name) <= 200
    AND (email IS NULL OR length(email) <= 320)
    AND (phone IS NULL OR length(phone) <= 40)
    AND (gender IS NULL OR length(gender) <= 200)
);

ALTER TABLE teams ADD CONSTRAINT teams_raw_name_length
    CHECK (length(name) <= 200);
