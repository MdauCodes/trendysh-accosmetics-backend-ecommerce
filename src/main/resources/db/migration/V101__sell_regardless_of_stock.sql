-- Sell regardless of stock (owner, 7 Nov 2026: "we must not show customers how many stock remaining since we have not yet
-- had a proper inventory management… out of stock must never limit adding to cart"). While on, every option reads as
-- available to customers (no stock numbers, no "sold out", nothing refused for stock), checkout holds stock where the
-- records have it and takes the order without a hold where they do not, and staff keep seeing the real figures. Turned on
-- now; Stock > Set up turns it off once the stock records can be trusted.
ALTER TABLE shop_settings ADD COLUMN sell_regardless_of_stock BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE shop_settings SET sell_regardless_of_stock = TRUE;
