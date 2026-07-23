CREATE TABLE sessions (
    user_id uuid NOT NULL REFERENCES users (id),
    token varchar(256) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    expires_at timestamptz NOT NULL
);
