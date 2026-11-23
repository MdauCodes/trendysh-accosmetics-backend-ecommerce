-- Why shoppers leave checkout (asked in a small box on the website). The visit may be forgotten after 13 months
-- while the reason stays, so the link is cleared rather than the row removed.
CREATE TABLE checkout_exit_reason (
    id         UUID         PRIMARY KEY,
    visit_id   UUID         NULL REFERENCES site_visit (id) ON DELETE SET NULL,
    step       VARCHAR(16)  NOT NULL,
    reason     VARCHAR(24)  NOT NULL,
    note       VARCHAR(300) NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_checkout_exit_reason_created ON checkout_exit_reason (created_at);
CREATE INDEX ix_checkout_exit_reason_visit ON checkout_exit_reason (visit_id);
