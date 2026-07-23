-- Requires PostgreSQL 13+ (for the built-in gen_random_uuid()).
CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(320) NOT NULL,
    email varchar(320) NOT NULL UNIQUE,
    password varchar(161) NOT NULL,
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT NOW()
);
