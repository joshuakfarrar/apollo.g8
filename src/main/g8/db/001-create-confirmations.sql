CREATE TABLE confirmations (
    user_id uuid NOT NULL REFERENCES users (id),
    code varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW()
);
