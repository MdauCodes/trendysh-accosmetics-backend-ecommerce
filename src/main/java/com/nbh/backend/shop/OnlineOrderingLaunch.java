package com.nbh.backend.shop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Online ordering opens by itself at the launch (owner, 2 Nov 2026: "the whatsapp mode must switch automatically to real
 * checkouts at noon"). It goes through {@link ShopSettingsService}, so every open storefront follows without a reload.
 * It acts once: only while nobody has ever flipped the switch. After that the switch is the owner's, so setting WHATSAPP
 * again stays that way. A backend that was down at the launch time catches up within a minute of starting.
 */
@Slf4j
@Component
public class OnlineOrderingLaunch {

    static final String CHANGED_BY = "Automatic at the launch";

    private final ShopSettingsService settings;
    private final Clock clock;
    private final Instant opensAt;

    public OnlineOrderingLaunch(ShopSettingsService settings, Clock clock,
            @Value("${app.shop.online-ordering-opens-at:2026-11-02T09:00:00Z}") Instant opensAt) {
        this.settings = settings;
        this.clock = clock;
        this.opensAt = opensAt;
    }

    @Scheduled(cron = "0 * * * * *")
    public void openWhenDue() {
        if (clock.instant().isBefore(opensAt)) {
            return;
        }
        ShopSettings current = settings.get();
        if (current.getOrderingMode() == OrderingMode.WHATSAPP && current.getChangedBy() == null) {
            settings.setOrderingMode(OrderingMode.FULL, CHANGED_BY);
            log.info("Online ordering opened automatically at the launch");
        }
    }
}
