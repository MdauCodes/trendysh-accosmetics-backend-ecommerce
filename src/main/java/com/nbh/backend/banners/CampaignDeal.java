package com.nbh.backend.banners;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One deal of a campaign (V96, owner 2026-11-04): a product (every shade and size of it) or one option of it, at a % off,
 * an amount off each piece, or "buy X get Y free". The campaign is the builder banner it belongs to, so the banner's cards
 * are always exactly its deals. See {@link CampaignDealService}.
 */
@Getter
@Setter
@Entity
@Table(name = "campaign_deal")
public class CampaignDeal extends BaseEntity {

    public enum Type {
        /** {@code percentOff} off the price. */
        PERCENT,
        /** {@code amountOff} shillings off each piece. */
        AMOUNT,
        /** Pay for {@code buyQty}, the shop adds {@code freeQty} more free (any shades of the same product). */
        BUY_GET
    }

    @Column(name = "banner_id", nullable = false)
    private UUID bannerId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** One option only; null = every shade and size of the product. */
    @Column(name = "variant_id")
    private UUID variantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "deal_type", nullable = false, length = 8)
    private Type type;

    @Column(name = "percent_off", precision = 5, scale = 2)
    private BigDecimal percentOff;

    @Column(name = "amount_off", precision = 14, scale = 2)
    private BigDecimal amountOff;

    @Column(name = "buy_qty")
    private Integer buyQty;

    @Column(name = "free_qty")
    private Integer freeQty;

    /** "First 20 pieces at this price": pieces sold under the deal, free ones included; null = while stock lasts. */
    @Column(name = "cap_qty")
    private Integer capQty;

    /** This deal's own end, before the campaign's; null = the campaign's. */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private int position;
}
