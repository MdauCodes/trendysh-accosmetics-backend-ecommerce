package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.PromoCampaign;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * The one place a variant's real selling price is computed. Used on every read path that shows or
 * charges a price — the public catalogue (browse/detail, for display) and {@code CartService}
 * (for the price actually locked into a cart line, {@code CartItem.unitPriceSnapshot}). Checkout
 * itself never re-resolves a price: it trusts the cart's snapshot, the same way it already trusts
 * nothing else about the client — the snapshot is what makes a price server-computed and
 * tamper-proof without needing checkout to duplicate this logic.
 *
 * Rule: a campaign deal (% or amount off, owner 2026-11-04) wins over a variant's own flash deal, which wins over an
 * active site-wide campaign (most-specific-wins), so a product can never be discounted twice by accident. A "buy X get Y
 * free" deal leaves the price of each piece alone (the free pieces are separate basket lines, see CartService) but is
 * carried on the answer so the shop can say so.
 *
 * A variant may also carry a wholesale tier ({@code wholesaleMinQty}/{@code wholesalePrice}) — once
 * {@code qty} reaches that threshold, its per-unit price is compared against whatever the deal/
 * campaign logic above already produced, and the cheaper of the two wins ("cheapest wins", not
 * "most-specific wins": a wholesale buyer should never pay more than a flash-deal shopper would).
 * No tier applies while the shop's wholesale switch is off, nor to a product on a campaign deal unless that campaign
 * says wholesale still applies. Callers with no meaningful cart quantity (e.g. building a storefront card) pass {@link
 * BigDecimal#ZERO}, which can never qualify for a tier.
 */
@Service
public class EffectivePriceResolver {

    public enum Source { NONE, PRODUCT_DEAL, CAMPAIGN, WHOLESALE, CAMPAIGN_DEAL }

    /** {@code deal}: the campaign deal the variant is on (any type), or null. */
    public record EffectivePrice(BigDecimal originalPrice, BigDecimal effectivePrice, Instant dealEndsAt, Source source,
                                 LiveDeals.LiveDeal deal) {
        public EffectivePrice(BigDecimal originalPrice, BigDecimal effectivePrice, Instant dealEndsAt, Source source) {
            this(originalPrice, effectivePrice, dealEndsAt, source, null);
        }

        public boolean onDeal() {
            return source != Source.NONE;
        }
    }

    private final Clock clock;
    private final LiveDeals deals;
    private final BooleanSupplier wholesaleOn;

    /** No campaign deals and wholesale always on: for tests that build the resolver by hand. */
    public EffectivePriceResolver(Clock clock) {
        this(clock, null, () -> true);
    }

    @Autowired
    public EffectivePriceResolver(Clock clock, @Lazy LiveDeals deals,
                                  @Lazy com.nbh.backend.shop.ShopSettingsService shopSettings) {
        this(clock, deals, (BooleanSupplier) shopSettings);
    }

    public EffectivePriceResolver(Clock clock, LiveDeals deals, BooleanSupplier wholesaleOn) {
        this.clock = clock;
        this.deals = deals;
        this.wholesaleOn = wholesaleOn;
    }

    /** Whether the shop's wholesale prices are on (owner, 2026-11-04): off, no tier is charged or shown. */
    public boolean wholesaleOn() {
        return wholesaleOn.getAsBoolean();
    }

    /**
     * @param activeCampaign the currently-active campaign, or null if none is active right now
     * @param qty the quantity being priced (e.g. a cart line's total), or null/zero if there is no
     *        meaningful quantity context (e.g. a storefront card) — never qualifies for wholesale
     * @throws IllegalStateException if the variant has no price — callers on every sellable path
     *         must filter to {@link ProductVariant#isSellable()} first (Phase 3, 2026-09-24: a
     *         draft variant can now be saved with no price at all).
     */
    public EffectivePrice resolve(ProductVariant variant, PromoCampaign activeCampaign, BigDecimal qty) {
        BigDecimal original = variant.getSellingPrice();
        if (original == null) {
            throw new IllegalStateException("Variant " + variant.getId() + " has no price — filter to isSellable() first");
        }
        // A package's price is already a fixed, admin-chosen discount off its components' combined price
        // (see PackageService) — stacking a site-wide campaign or a per-variant deal on top of it would
        // silently discount it further than intended, and the public "Shop by profession" modal
        // always shows this same plain sellingPrice, so applying either here would make the price a
        // customer sees there disagree with what the shop grid and cart would charge.
        if (variant.isPackage()) {
            return new EffectivePrice(original, original, null, Source.NONE);
        }
        Instant now = Instant.now(clock);
        LiveDeals.LiveDeal deal = deals == null ? null : deals.dealFor(variant);

        EffectivePrice nonWholesale;
        if (deal != null && !deal.isBuyGet() && deal.priceOf(original).compareTo(original) < 0) {
            nonWholesale = new EffectivePrice(original, deal.priceOf(original), deal.endsAt(), Source.CAMPAIGN_DEAL, deal);
        } else if (variant.getDealPrice() != null && variant.getDealEndsAt() != null && variant.getDealEndsAt().isAfter(now)) {
            nonWholesale = new EffectivePrice(original, variant.getDealPrice(), variant.getDealEndsAt(), Source.PRODUCT_DEAL, deal);
        } else if (activeCampaign != null) {
            BigDecimal factor = BigDecimal.ONE.subtract(
                    activeCampaign.getPercentOff().movePointLeft(2));
            BigDecimal discounted = original.multiply(factor).setScale(2, RoundingMode.HALF_UP);
            nonWholesale = new EffectivePrice(original, discounted, activeCampaign.getEndsAt(), Source.CAMPAIGN, deal);
        } else {
            nonWholesale = new EffectivePrice(original, original, null, Source.NONE, deal);
        }

        boolean tierAllowed = wholesaleOn.getAsBoolean() && (deal == null || deal.wholesaleApplies());
        if (tierAllowed && variant.getWholesaleMinQty() != null && variant.getWholesalePrice() != null && qty != null
                && qty.compareTo(BigDecimal.valueOf(variant.getWholesaleMinQty())) >= 0
                && variant.getWholesalePrice().compareTo(nonWholesale.effectivePrice()) < 0) {
            return new EffectivePrice(original, variant.getWholesalePrice(), null, Source.WHOLESALE, deal);
        }
        return nonWholesale;
    }
}
