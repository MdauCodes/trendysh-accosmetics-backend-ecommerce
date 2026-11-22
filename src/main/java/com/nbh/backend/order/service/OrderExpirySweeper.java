package com.nbh.backend.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runs every minute, right behind the stock-hold expiry sweeper. Since the owner's decision (Nov 2026) it does
 * nothing by default: an unpaid order stays PENDING_PAYMENT until staff confirm the payment or cancel it, while the
 * stock hold still lapses on its own ({@code StockReservationExpirySweeper}). Set {@code app.orders.expire-unpaid=true}
 * to bring back the old behaviour of moving unpaid orders with no live stock hold to EXPIRED.
 */
@Slf4j
@Component
public class OrderExpirySweeper {

    private final OrderExpiryService expiryService;
    private boolean expireUnpaid; // not final: tests flip it on the shared context

    public OrderExpirySweeper(OrderExpiryService expiryService,
                              @Value("${app.orders.expire-unpaid:false}") boolean expireUnpaid) {
        this.expiryService = expiryService;
        this.expireUnpaid = expireUnpaid;
    }

    /**
     * One pass over every unpaid order; a single bad row never stops the rest. Returns how many expired (always 0
     * while {@code app.orders.expire-unpaid} is off).
     */
    @Scheduled(fixedDelayString = "${app.orders.expiry-sweep-interval-ms:60000}",
            initialDelayString = "${app.orders.expiry-sweep-initial-delay-ms:30000}")
    public int sweepUnpaidOrders() {
        if (!expireUnpaid) {
            return 0;
        }
        int expired = 0;
        for (UUID id : expiryService.unpaidOrderIds()) {
            try {
                if (expiryService.expireIfLapsed(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to expire order {}: {}", id, e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Expired {} unpaid order(s) whose stock holds had lapsed", expired);
        }
        return expired;
    }
}
