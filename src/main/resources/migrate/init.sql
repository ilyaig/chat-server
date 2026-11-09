CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(32) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tokens (
    token      TEXT PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS messages (
    id           BIGSERIAL PRIMARY KEY,
    sender_id    BIGINT NOT NULL REFERENCES users(id),
    recipient_id BIGINT NOT NULL REFERENCES users(id),
    text         TEXT NOT NULL,
    status       SMALLINT NOT NULL DEFAULT 0,  -- 0=SENT, 1=DELIVERED, 2=READ
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    read_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_messages_pair
    ON messages (LEAST(sender_id, recipient_id), GREATEST(sender_id, recipient_id), id);