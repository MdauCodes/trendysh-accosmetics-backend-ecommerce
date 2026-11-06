package com.nbh.backend.stock.service;

import com.nbh.backend.shop.ShopSettings;
import com.nbh.backend.shop.ShopSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What customers may order (owner, 2026-11-07: "we must not show customers how many stock remaining since we have not yet
 * had a proper inventory management… out of stock must never limit adding to cart"). Normally the real availability
 * ({@link StockAvailabilityService#available}); while the shop sells regardless of stock (V101), plenty of every option
 * ({@link StockTrackingService#UNTRACKED_AVAILABLE}, the figure an untracked option already shows), so nothing a customer
 * sees is limited, counted or marked sold out by stock. Staff screens keep reading {@link StockAvailabilityService}.
 */
@Service
public class ShopperStock {

    private final StockAvailabilityService availability;
    private final ShopSettingsRepository settings;

    public ShopperStock(StockAvailabilityService availability, ShopSettingsRepository settings) {
        this.availability = availability;
        this.settings = settings;
    }

    /** Whether the shop sells regardless of stock right now. */
    @Transactional(readOnly = true)
    public boolean sellingRegardless() {
        return settings.findFirstByOrderByCreatedAtAsc().map(ShopSettings::isSellRegardlessOfStock).orElse(false);
    }

    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> available(Collection<UUID> variantIds) {
        if (!sellingRegardless()) return availability.available(variantIds);
        Map<UUID, BigDecimal> plenty = new HashMap<>();
        for (UUID id : variantIds) plenty.put(id, StockTrackingService.UNTRACKED_AVAILABLE);
        return plenty;
    }

    @Transactional(readOnly = true)
    public BigDecimal available(UUID variantId) {
        return sellingRegardless() ? StockTrackingService.UNTRACKED_AVAILABLE : availability.available(variantId);
    }

    /** Whether the shop's records have this many of an option free to hold (the real figure, whatever the setting). */
    @Transactional(readOnly = true)
    public boolean canHold(UUID variantId, BigDecimal qty) {
        return availability.available(variantId).compareTo(qty) >= 0;
    }
}
