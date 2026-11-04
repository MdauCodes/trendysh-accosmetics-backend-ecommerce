package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductVariant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * The campaign deal an option is on right now, if any (owner, 2026-11-04: campaigns and their deals; see
 * {@code banners.CampaignDealService}). Asked by {@link EffectivePriceResolver} on every price it works out, so an answer
 * must be quick: implementations keep what is live in memory for a few seconds.
 */
public interface LiveDeals {

    /** The live deal for this option, or null. A deal for one option wins over one for its whole product. */
    LiveDeal dealFor(ProductVariant variant);

    /**
     * A live deal as the shop needs it. {@code type} is PERCENT, AMOUNT or BUY_GET; {@code endsAt} the earlier of the
     * deal's and its campaign's end (null: while stock lasts); {@code left} how many more can be had at the deal, or null
     * when it is more than a handful (the shop says "Only 2 left" only when it is true and small).
     */
    record LiveDeal(UUID dealId, UUID campaignId, String campaignSlug, String campaignTitle, String type,
                    BigDecimal percentOff, BigDecimal amountOff, Integer buyQty, Integer freeQty, Instant endsAt,
                    boolean wholesaleApplies, Integer left) {

        public boolean isBuyGet() {
            return "BUY_GET".equals(type);
        }

        /** The price of one piece under a % or amount deal, in whole shillings, never below one; the price itself for BUY_GET. */
        public BigDecimal priceOf(BigDecimal price) {
            BigDecimal out = switch (type) {
                case "PERCENT" -> price.multiply(BigDecimal.valueOf(100).subtract(percentOff))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                case "AMOUNT" -> price.subtract(amountOff);
                default -> price;
            };
            return out.max(BigDecimal.ONE).setScale(2, RoundingMode.UNNECESSARY);
        }
    }
}
