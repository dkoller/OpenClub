-- Initial schema for migrations and jOOQ generation; no club API is exposed yet.
CREATE TABLE clubs (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
