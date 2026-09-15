-- Slate v2 initial schema.
-- Slates: full-replace declarative payloads pushed by agents.
-- Interactions: taps flowing back from devices (flattened columns + raw payload).
CREATE TABLE IF NOT EXISTS slates (
    slate_id     TEXT PRIMARY KEY,
    spec_json    TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    tone         TEXT NOT NULL DEFAULT 'neutral',
    updated_at   TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS interactions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    slate_id     TEXT NOT NULL REFERENCES slates(slate_id) ON DELETE CASCADE,
    kind         TEXT NOT NULL,
    element_id   TEXT,
    question_id  TEXT,
    option_id    TEXT,
    option_label TEXT,
    item_id      TEXT,
    value        TEXT,
    client_at    TEXT,
    stale        INTEGER NOT NULL DEFAULT 0,
    seq          TEXT NOT NULL UNIQUE,
    payload_json TEXT NOT NULL,
    created_at   TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_interactions_slate_id_id ON interactions(slate_id, id);
CREATE INDEX IF NOT EXISTS idx_interactions_created_at ON interactions(created_at);
