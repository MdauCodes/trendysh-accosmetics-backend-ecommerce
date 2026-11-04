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

-- The owner's face on the banners (owner, 2026-11-04): real photos of her (references), and the poses Gemini makes from
-- them, each approved by a person before use. A pose comes on a plain backdrop; the banner takes the backdrop's edge
-- colour, so she blends in with no join. Feet are left out unless a spot really needs a full-height figure.
CREATE TABLE owner_photo (
    id UUID PRIMARY KEY,
    image_url TEXT NOT NULL,
    note VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE owner_pose (
    id UUID PRIMARY KEY,
    -- LOOK, AMAZED, SURPRISED, MISS_OUT, WELCOME, POINTING
    mood VARCHAR(12) NOT NULL,
    -- HEAD (head and shoulders), WAIST, KNEE (the default for a standing figure), FULL (only when a spot asks)
    framing VARCHAR(6) NOT NULL,
    -- the side of the banner she stands on: LEFT or RIGHT (her pose turns toward the deals on the other side)
    side VARCHAR(5) NOT NULL,
    outfit VARCHAR(12) NOT NULL,
    -- the backdrop asked for, and the edge colour measured on the picture made (what the banner fills with)
    backdrop_hex VARCHAR(7) NOT NULL,
    edge_hex VARCHAR(7),
    -- MAKING, READY (to look at), APPROVED (usable), REJECTED, FAILED
    status VARCHAR(9) NOT NULL,
    image_url TEXT,
    width INT,
    height INT,
    -- where to place and crop her: focal point, face box (never cropped), safe box (head to hands), lowest cut (JSON)
    layout TEXT,
    brief TEXT,
    note TEXT,
    model VARCHAR(60),
    created_by VARCHAR(200),
    approved_by VARCHAR(200),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_owner_pose_status ON owner_pose (status, created_at DESC);

-- The pose a campaign's banner shows; none = the banner's own picture only.
ALTER TABLE promo_banner ADD COLUMN pose_id UUID REFERENCES owner_pose(id) ON DELETE SET NULL;
