package com.nbh.backend.shop;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnlineOrderingLaunchTest {

    private static final Instant NOON = Instant.parse("2026-11-02T09:00:00Z");

    private final ShopSettingsService settings = mock(ShopSettingsService.class);

    private void run(Instant now, OrderingMode mode, String changedBy) {
        ShopSettings row = new ShopSettings();
        row.setOrderingMode(mode);
        row.setChangedBy(changedBy);
        when(settings.get()).thenReturn(row);
        new OnlineOrderingLaunch(settings, Clock.fixed(now, ZoneOffset.UTC), NOON).openWhenDue();
    }

    @Test
    void beforeNoonNothingChanges() {
        run(NOON.minusSeconds(1), OrderingMode.WHATSAPP, null);
        verify(settings, never()).setOrderingMode(any(), any());
    }

    @Test
    void atNoonOnlineOrderingOpens() {
        run(NOON, OrderingMode.WHATSAPP, null);
        verify(settings).setOrderingMode(OrderingMode.FULL, OnlineOrderingLaunch.CHANGED_BY);
    }

    @Test
    void aBackendThatWasDownAtNoonCatchesUp() {
        run(NOON.plusSeconds(3_600), OrderingMode.WHATSAPP, null);
        verify(settings).setOrderingMode(OrderingMode.FULL, OnlineOrderingLaunch.CHANGED_BY);
    }

    @Test
    void whatsAppSetByAPersonAfterwardsStays() {
        run(NOON.plusSeconds(3_600), OrderingMode.WHATSAPP, "The Owner");
        verify(settings, never()).setOrderingMode(any(), any());
    }

    @Test
    void itDoesNotRepeatItselfOnceOpen() {
        run(NOON.plusSeconds(60), OrderingMode.FULL, OnlineOrderingLaunch.CHANGED_BY);
        verify(settings, never()).setOrderingMode(any(), any());
    }
}
