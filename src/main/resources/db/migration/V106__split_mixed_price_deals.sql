-- A product whose sizes cost different amounts gets one deal per size (owner, 2026-11-25), not one deal for the whole
-- product. Only live-or-waiting campaigns (banner and deal not yet ended) are touched; a product whose sizes all cost the
-- same stays one deal.
--
-- Each split keeps the original deal row for the product's first size (by sort order, then id), so its id, the orders that
-- were priced under it and the baskets that point at it stay. Every other size gets a new row with the same terms; the
-- limit (cap_qty) is the same for each size. An AMOUNT deal keeps its amount_off for every size: saving a campaign already
-- refuses an amount at or above the cheapest size's price, so it is below every size's price.
-- Positions in each affected banner are renumbered 0..n-1 with the new rows right after their original, in size order.
-- Campaign status is not changed. One statement, so a test can run it as it is.
WITH targets AS (
    SELECT d.id AS deal_id, d.banner_id, d.product_id, d.position, d.deal_type, d.percent_off, d.amount_off,
           d.buy_qty, d.free_qty, d.cap_qty, d.ends_at
    FROM campaign_deal d
    JOIN promo_banner b ON b.id = d.banner_id
    JOIN product p ON p.id = d.product_id
    WHERE d.variant_id IS NULL
      AND (b.ends_at IS NULL OR b.ends_at > now())
      AND (d.ends_at IS NULL OR d.ends_at > now())
      AND p.is_published AND NOT p.deleted AND NOT p.is_mock_data
      AND (SELECT COUNT(DISTINCT v.selling_price) FROM product_variant v
           WHERE v.product_id = d.product_id AND NOT v.deleted AND v.is_active
             AND v.selling_price IS NOT NULL AND NOT v.is_package) > 1
),
sizes AS (
    SELECT t.deal_id, v.id AS variant_id,
           ROW_NUMBER() OVER (PARTITION BY t.deal_id ORDER BY v.sort_order, v.id) AS rn
    FROM targets t
    JOIN product_variant v ON v.product_id = t.product_id
    WHERE NOT v.deleted AND v.is_active AND v.selling_price IS NOT NULL AND NOT v.is_package
),
affected AS (SELECT DISTINCT banner_id FROM targets),
-- every deal of an affected banner in its final order: one line per size for a split deal, one line for any other
lines AS (
    SELECT d.id AS deal_id, d.banner_id, s.variant_id, COALESCE(s.rn, 1) AS rn,
           ROW_NUMBER() OVER (PARTITION BY d.banner_id
                              ORDER BY d.position, d.created_at, d.id, COALESCE(s.rn, 1)) - 1 AS new_position
    FROM campaign_deal d
    JOIN affected a ON a.banner_id = d.banner_id
    LEFT JOIN sizes s ON s.deal_id = d.id
),
kept AS (
    UPDATE campaign_deal d
    SET variant_id = COALESCE(l.variant_id, d.variant_id), position = l.new_position, updated_at = now()
    FROM lines l
    WHERE l.deal_id = d.id AND l.rn = 1
    RETURNING d.id
)
INSERT INTO campaign_deal (id, banner_id, product_id, variant_id, deal_type, percent_off, amount_off, buy_qty, free_qty,
                           cap_qty, ends_at, position)
SELECT gen_random_uuid(), t.banner_id, t.product_id, l.variant_id, t.deal_type, t.percent_off, t.amount_off, t.buy_qty,
       t.free_qty, t.cap_qty, t.ends_at, l.new_position
FROM lines l
JOIN targets t ON t.deal_id = l.deal_id
WHERE l.rn > 1;
