package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.PromoCampaign;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Campaign deals and the wholesale switch in the price resolver (owner, 2026-11-04): a campaign deal wins over a flash
 * deal and a site-wide campaign; buy X get Y leaves the price alone; wholesale applies only while the switch is on and the
 * campaign allows it.
 */
class CampaignDealPricingUnitTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicBoolean wholesaleOn = new AtomicBoolean(true);
    private LiveDeals.LiveDeal current;
    private final EffectivePriceResolver resolver = new EffectivePriceResolver(clock, v -> current, wholesaleOn::get);

    private static ProductVariant variant(String price) {
        ProductVariant v = new ProductVariant();
        v.setSellingPrice(new BigDecimal(price));
        return v;
    }

    private static LiveDeals.LiveDeal percent(String pct, boolean wholesaleApplies) {
        return new LiveDeals.LiveDeal(UUID.randomUUID(), UUID.randomUUID(), "weekend", "Weekend Beauty Challenge",
                "PERCENT", new BigDecimal(pct), null, null, null, NOW.plusSeconds(3600), wholesaleApplies, null);
    }

    private static LiveDeals.LiveDeal amount(String off) {
        return new LiveDeals.LiveDeal(UUID.randomUUID(), UUID.randomUUID(), "weekend", "Weekend", "AMOUNT", null,
                new BigDecimal(off), null, null, null, false, 2);
    }

    private static LiveDeals.LiveDeal buyGet(int buy, int free) {
        return new LiveDeals.LiveDeal(UUID.randomUUID(), UUID.randomUUID(), "weekend", "Weekend", "BUY_GET", null,
                null, buy, free, null, false, null);
    }

    @Test
    void aPercentDealIsWholeShillings() {
        current = percent("20", false);
        var r = resolver.resolve(variant("349"), null, BigDecimal.ONE);
        // 349 * 0.8 = 279.2 -> 279
        assertThat(r.effectivePrice()).isEqualByComparingTo("279");
        assertThat(r.source()).isEqualTo(EffectivePriceResolver.Source.CAMPAIGN_DEAL);
        assertThat(r.dealEndsAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(r.deal()).isSameAs(current);
    }

    @Test
    void anAmountDealTakesShillingsOffEachPiece() {
        current = amount("500");
        var r = resolver.resolve(variant("2500"), null, BigDecimal.ONE);
        assertThat(r.effectivePrice()).isEqualByComparingTo("2000");
        assertThat(r.onDeal()).isTrue();
    }

    @Test
    void aCampaignDealWinsOverAFlashDealAndTheSiteWideCampaign() {
        current = percent("10", false);
        var v = variant("1000");
        v.setDealPrice(new BigDecimal("700"));
        v.setDealEndsAt(NOW.plusSeconds(60));
        PromoCampaign site = new PromoCampaign();
        site.setPercentOff(new BigDecimal("50"));
        site.setEndsAt(NOW.plusSeconds(60));

        var r = resolver.resolve(v, site, BigDecimal.ONE);
        assertThat(r.effectivePrice()).isEqualByComparingTo("900");
        assertThat(r.source()).isEqualTo(EffectivePriceResolver.Source.CAMPAIGN_DEAL);
    }

    @Test
    void buyGetLeavesThePriceAloneButIsCarried() {
        current = buyGet(5, 3);
        var r = resolver.resolve(variant("350"), null, BigDecimal.valueOf(5));
        assertThat(r.effectivePrice()).isEqualByComparingTo("350");
        assertThat(r.source()).isEqualTo(EffectivePriceResolver.Source.NONE);
        assertThat(r.deal().isBuyGet()).isTrue();
    }

    @Test
    void wholesaleIsIgnoredWhileTheSwitchIsOff() {
        current = null;
        var v = variant("200");
        v.setWholesaleMinQty(6);
        v.setWholesalePrice(new BigDecimal("150"));

        assertThat(resolver.resolve(v, null, BigDecimal.valueOf(6)).effectivePrice()).isEqualByComparingTo("150");
        wholesaleOn.set(false);
        var off = resolver.resolve(v, null, BigDecimal.valueOf(6));
        assertThat(off.effectivePrice()).isEqualByComparingTo("200");
        assertThat(off.source()).isEqualTo(EffectivePriceResolver.Source.NONE);
        assertThat(resolver.wholesaleOn()).isFalse();
    }

    @Test
    void wholesaleOnACampaignProductOnlyWhenTheCampaignAllowsIt() {
        var v = variant("200");
        v.setWholesaleMinQty(6);
        v.setWholesalePrice(new BigDecimal("120"));

        current = percent("10", false);
        assertThat(resolver.resolve(v, null, BigDecimal.valueOf(6)).effectivePrice()).isEqualByComparingTo("180");

        current = percent("10", true);
        var r = resolver.resolve(v, null, BigDecimal.valueOf(6));
        assertThat(r.effectivePrice()).isEqualByComparingTo("120");
        assertThat(r.source()).isEqualTo(EffectivePriceResolver.Source.WHOLESALE);
    }

    @Test
    void aPackageIsNeverOnACampaignDeal() {
        current = percent("50", false);
        var v = variant("5000");
        v.setPackage(true);
        assertThat(resolver.resolve(v, null, BigDecimal.ONE).effectivePrice()).isEqualByComparingTo("5000");
    }

    @Test
    void anAmountNeverTakesThePriceBelowOneShilling() {
        assertThat(amount("500").priceOf(new BigDecimal("300"))).isEqualByComparingTo("1");
    }
}
