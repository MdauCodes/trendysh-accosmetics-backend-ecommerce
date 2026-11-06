package com.nbh.backend.support;

import com.nbh.backend.shop.ShopSettingsRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Tests run with stock limits on. V101 turns "sell regardless of stock" on for the live shop (owner, 2026-11-07), but the
 * tests of what stock refuses, holds and reports are about the shop once it is off again; the tests of the switch itself
 * turn it on for their own run and back off.
 */
@Component
@Profile("test")
public class StockLimitsInTests implements ApplicationRunner {

    private final ShopSettingsRepository settings;

    public StockLimitsInTests(ShopSettingsRepository settings) {
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        settings.findFirstByOrderByCreatedAtAsc().ifPresent(s -> {
            if (s.isSellRegardlessOfStock()) {
                s.setSellRegardlessOfStock(false);
                settings.save(s);
            }
        });
    }
}
