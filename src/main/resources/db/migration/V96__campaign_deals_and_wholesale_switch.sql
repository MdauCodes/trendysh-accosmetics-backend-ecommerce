-- Campaigns (owner, 2026-11-04): a campaign IS its banner (a builder banner, V86), and its deals are the cards on it, so
-- banner and deals never drift apart. A deal is a product (all its shades and sizes) or one option of it, at a % off, an
-- amount off, or "buy X get Y free". A banner with no deals sells the shop as a whole. And one switch turns wholesale
-- prices off (and back on) for the whole shop.

-- The campaign's own page (/deals/<slug>), how its cards show (products, categories, or chosen for it), and whether a
-- deal product's wholesale price still applies while the campaign runs (the cheaper of the two, never both).
ALTER TABLE promo_banner ADD COLUMN slug VARCHAR(80);
-- the campaign's name as customers see it ("Weekend Beauty Challenge"); the banner's own name stays staff's
ALTER TABLE promo_banner ADD COLUMN title VARCHAR(60);
ALTER TABLE promo_banner ADD COLUMN show_as VARCHAR(10) NOT NULL DEFAULT 'AUTO';
ALTER TABLE promo_banner ADD COLUMN wholesale_applies BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX uq_promo_banner_slug ON promo_banner (slug) WHERE slug IS NOT NULL;

CREATE TABLE campaign_deal (
    id UUID PRIMARY KEY,
    banner_id UUID NOT NULL REFERENCES promo_banner(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    -- one option only; none = every shade and size of the product
    variant_id UUID REFERENCES product_variant(id) ON DELETE CASCADE,
    -- PERCENT (percent_off), AMOUNT (amount_off, KSh per piece) or BUY_GET (buy_qty paid, free_qty added free)
    deal_type VARCHAR(8) NOT NULL,
    percent_off NUMERIC(5, 2),
    amount_off NUMERIC(14, 2),
    buy_qty INT,
    free_qty INT,
    -- "first 20 pieces at this price": pieces sold under the deal, free ones included; none = while stock lasts
    cap_qty INT,
    -- this deal's own end, before the campaign's; none = the campaign's
    ends_at TIMESTAMPTZ,
    position INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_campaign_deal_type CHECK (
        (deal_type = 'PERCENT' AND percent_off > 0 AND percent_off < 100)
        OR (deal_type = 'AMOUNT' AND amount_off > 0)
        OR (deal_type = 'BUY_GET' AND buy_qty >= 1 AND free_qty >= 1)),
    CONSTRAINT ck_campaign_deal_cap CHECK (cap_qty IS NULL OR cap_qty > 0)
);

CREATE INDEX idx_campaign_deal_banner ON campaign_deal (banner_id, position);
CREATE INDEX idx_campaign_deal_product ON campaign_deal (product_id);

-- A basket line the shop added free under a buy-X-get-Y deal (its choice_key is "free:<deal id>", so it never merges
-- with the paid line of the same option), and on the order: which deal priced a line, so a deal's cap can be counted.
ALTER TABLE cart_item ADD COLUMN free_deal_id UUID REFERENCES campaign_deal(id) ON DELETE SET NULL;
ALTER TABLE customer_order_item ADD COLUMN campaign_deal_id UUID REFERENCES campaign_deal(id) ON DELETE SET NULL;
ALTER TABLE customer_order_item ADD COLUMN free_under_deal BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_order_item_campaign_deal ON customer_order_item (campaign_deal_id) WHERE campaign_deal_id IS NOT NULL;

-- A campaign may run "while stock lasts": no end date, it ends when its last deal sells out.
-- (promo_banner.ends_at was already nullable; staff were only made to fill it in.)

-- Wholesale prices on or off for the whole shop. Off: none is charged or shown, but every product's tier stays saved.
ALTER TABLE shop_settings ADD COLUMN wholesale_on BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE shop_settings ADD COLUMN wholesale_changed_by VARCHAR(200);
