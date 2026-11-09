-- Where shoppers come from, and what they buy (docs/plans/traffic-sources-dashboard.md, owner 2026-11-09).
-- Additive only: two new tables for visits and what happened in them, the source on each order, and the ad spend
-- staff type in until Google Ads reports it by itself.

CREATE TABLE site_visit (
    id          UUID PRIMARY KEY,
    -- A random id kept on the shopper's browser: only past one visit when they allowed cookies, otherwise new each visit.
    visitor     UUID         NOT NULL,
    channel     VARCHAR(24)  NOT NULL,
    source      VARCHAR(80),
    campaign    VARCHAR(120),
    landing     VARCHAR(300),
    device      VARCHAR(10),
    started_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_site_visit_started ON site_visit (started_at);
CREATE INDEX ix_site_visit_visitor ON site_visit (visitor, started_at);

CREATE TABLE site_event (
    id        BIGSERIAL PRIMARY KEY,
    visit_id  UUID        NOT NULL REFERENCES site_visit (id) ON DELETE CASCADE,
    kind      VARCHAR(24) NOT NULL,
    value     NUMERIC(14, 2),
    at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_site_event_visit ON site_event (visit_id);
CREATE INDEX ix_site_event_at ON site_event (at);

-- The order keeps its source for good (visits are removed after 13 months).
ALTER TABLE customer_order
    ADD COLUMN visit_id      UUID,
    ADD COLUMN first_channel VARCHAR(24),
    ADD COLUMN first_source  VARCHAR(80),
    ADD COLUMN last_channel  VARCHAR(24),
    ADD COLUMN last_source   VARCHAR(80),
    ADD COLUMN last_campaign VARCHAR(120);

CREATE TABLE ad_spend (
    day         DATE           NOT NULL,
    channel     VARCHAR(24)    NOT NULL,
    amount_kes  NUMERIC(14, 2) NOT NULL,
    note        VARCHAR(200),
    updated_at  TIMESTAMPTZ    NOT NULL,
    updated_by  VARCHAR(200),
    PRIMARY KEY (day, channel)
);
