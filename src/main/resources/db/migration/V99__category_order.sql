-- The shop's categories in the owner's order everywhere they are listed (owner, 6 Nov 2026: "nails, lashes, makeup, skin care,
-- PMU, DIY..."): the hero, the menus and filters, and the pages built for search engines all follow sort_order. Every top-level
-- category was 0 before, so the order was whatever the database returned. Staff can still change it under Categories
-- (Position in the list); a category not named here keeps its own place, after these.
UPDATE category c SET sort_order = v.pos
FROM (VALUES
    ('nails-manicure-pedicure', 10),
    ('lashes', 20),
    ('make-up', 30),
    ('skin-care', 40),
    ('pmu-permanent-make-up', 50),
    ('for-personal-diy', 60),
    ('spa-business-and-diy-packages', 70)
) AS v(slug, pos)
WHERE c.slug = v.slug AND c.parent_id IS NULL;

UPDATE category SET sort_order = 100 WHERE parent_id IS NULL AND sort_order = 0;
