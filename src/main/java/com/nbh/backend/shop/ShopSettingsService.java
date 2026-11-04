package com.nbh.backend.shop;

import com.nbh.backend.common.event.DataChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.BooleanSupplier;

/**
 * The shop's one settings row: how it takes orders ({@link OrderingMode}) and whether wholesale prices are on. Changing
 * either tells every open storefront (the same live hint a catalogue change sends), so a shopper's page follows the
 * switch without a reload.
 */
@Service
public class ShopSettingsService implements BooleanSupplier {

    /** How long the wholesale switch is remembered between reads: every price looked up asks it. */
    private static final long WHOLESALE_MEMORY_MS = 15_000;

    private final ShopSettingsRepository repository;
    private final ApplicationEventPublisher events;

    private volatile Boolean wholesaleOn;
    private volatile long wholesaleReadAt;

    public ShopSettingsService(ShopSettingsRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /** The one settings row; created on the fly if a database somehow has none. */
    @Transactional
    public ShopSettings get() {
        return repository.findFirstByOrderByCreatedAtAsc().orElseGet(() -> repository.save(new ShopSettings()));
    }

    /** Saying the mode it already has changes nothing and tells nobody. */
    @Transactional
    public ShopSettings setOrderingMode(OrderingMode mode, String changedBy) {
        ShopSettings settings = get();
        if (settings.getOrderingMode() == mode) {
            return settings;
        }
        settings.setOrderingMode(mode);
        settings.setChangedBy(changedBy);
        ShopSettings saved = repository.save(settings);
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
        return saved;
    }

    /**
     * Wholesale prices on or off for the whole shop (owner, 2026-11-04). Every product keeps its tier either way; the price
     * resolver and the shop simply stop using them while it is off. Saying what it already is changes nothing.
     */
    @Transactional
    public ShopSettings setWholesaleOn(boolean on, String changedBy) {
        ShopSettings settings = get();
        if (settings.isWholesaleOn() == on) {
            return settings;
        }
        settings.setWholesaleOn(on);
        settings.setWholesaleChangedBy(changedBy);
        ShopSettings saved = repository.save(settings);
        wholesaleOn = on;
        wholesaleReadAt = System.currentTimeMillis();
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
        return saved;
    }

    /** Whether wholesale prices are on, remembered for a few seconds (every card and basket line asks). */
    public boolean wholesaleOn() {
        Boolean known = wholesaleOn;
        if (known == null || System.currentTimeMillis() - wholesaleReadAt > WHOLESALE_MEMORY_MS) {
            known = repository.findFirstByOrderByCreatedAtAsc().map(ShopSettings::isWholesaleOn).orElse(true);
            wholesaleOn = known;
            wholesaleReadAt = System.currentTimeMillis();
        }
        return known;
    }

    /** The wholesale switch, for {@link com.nbh.backend.catalog.service.EffectivePriceResolver}. */
    @Override
    public boolean getAsBoolean() {
        return wholesaleOn();
    }
}
