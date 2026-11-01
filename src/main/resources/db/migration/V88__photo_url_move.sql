-- Moving the shop's photos off Cloudinary into its own bucket (Nov 2026, see PhotoMoveService).
-- photo_url_copy: each Cloudinary address found in the database, where its copy is and how it was checked.
-- photo_url_move: every value the move rewrote, old and new, so the move can be undone row by row.
CREATE TABLE photo_url_copy (
    old_url     TEXT PRIMARY KEY,
    bucket_key  TEXT NOT NULL,
    new_url     TEXT NOT NULL,
    bytes       BIGINT,
    sha256      VARCHAR(64),
    status      VARCHAR(16) NOT NULL,
    note        TEXT,
    checked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE photo_url_move (
    id           BIGSERIAL PRIMARY KEY,
    table_name   TEXT NOT NULL,
    column_name  TEXT NOT NULL,
    row_id       TEXT NOT NULL,
    old_value    TEXT NOT NULL,
    new_value    TEXT NOT NULL,
    moved_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    undone_at    TIMESTAMPTZ
);

CREATE INDEX photo_url_move_open ON photo_url_move (id) WHERE undone_at IS NULL;
