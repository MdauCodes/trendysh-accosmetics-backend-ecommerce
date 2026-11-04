package com.nbh.backend.shop;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Singleton row (created by V71): how the shop takes orders. See {@link OrderingMode}. {@code changedBy} is who last
 * flipped the switch (the staff member's name), null until someone has.
 */
@Getter
@Setter
@Entity
@Table(name = "shop_settings")
public class ShopSettings extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "ordering_mode", nullable = false, length = 20)
    private OrderingMode orderingMode = OrderingMode.WHATSAPP;

    @Column(name = "changed_by", length = 200)
    private String changedBy;

    /** Every option in the shop with no stock number yet can be ordered without a stock limit until it is counted (V78). */
    @Column(name = "sell_without_stock", nullable = false)
    private boolean sellWithoutStock = false;

    /**
     * Wholesale prices on or off for the whole shop (V96, owner 2026-11-04). Off: no tier is charged or shown, but every
     * product keeps its tier, so switching back on brings them all back as they were.
     */
    @Column(name = "wholesale_on", nullable = false)
    private boolean wholesaleOn = true;

    /** Who last flipped the wholesale switch; null until someone has. */
    @Column(name = "wholesale_changed_by", length = 200)
    private String wholesaleChangedBy;
}
