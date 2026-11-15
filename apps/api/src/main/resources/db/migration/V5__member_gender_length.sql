-- Match the existing Kotlin member-field validation at the database boundary.
-- Do not restrict gender to a fixed list; club policy controls player requirements.
ALTER TABLE members ADD CONSTRAINT members_gender_length
    CHECK (gender IS NULL OR length(trim(gender)) BETWEEN 1 AND 200);
