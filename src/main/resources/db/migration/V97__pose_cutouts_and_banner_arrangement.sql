-- The owner taken out of her green screen, so she can stand on any banner colour (owner, 2026-11-04). Empty on poses made
-- before this: those keep blending into their own backdrop colour.
ALTER TABLE owner_pose ADD COLUMN cutout_url TEXT;

-- How staff arranged a campaign banner, as JSON (CampaignDealService.Arrangement): its colour, where her pose and how big
-- for phone and laptop, and the order of the pose, the words and the cards. Empty: the usual arrangement.
ALTER TABLE promo_banner ADD COLUMN arrangement TEXT;
