package com.nbh.backend.order.service;

import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * The next-day check (owner, 2 Nov 2026): deliveries that left before today and whose customer has not been asked yet whether
 * they arrived. Staff send it on WhatsApp in one tap; pressing it takes the order off the list. Older than two weeks, it is
 * no longer worth asking.
 */
@Service
public class OrderReceivedCheckService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final Duration TOO_OLD = Duration.ofDays(14);

    public record ReceivedCheck(UUID id, String orderNo, String customerName, String guestPhone, String deliveryAddress,
                                Instant dispatchedAt) {}

    private final CustomerOrderRepository orders;
    private final Clock clock;

    public OrderReceivedCheckService(CustomerOrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ReceivedCheck> waiting() {
        Instant now = clock.instant();
        Instant startOfToday = now.atZone(NAIROBI).toLocalDate().atStartOfDay(NAIROBI).toInstant();
        return orders.waitingForReceivedCheck(startOfToday, now.minus(TOO_OLD)).stream()
                .map(o -> new ReceivedCheck(o.getId(), o.getOrderNo(), o.getCustomerName(), o.getGuestPhone(),
                        o.getDeliveryAddress(), o.getDispatchedAt()))
                .toList();
    }

    @Transactional
    public void markSent(UUID orderId) {
        CustomerOrder order = orders.findById(orderId).orElseThrow(() -> new NotFoundException("No such order"));
        if (order.getReceivedCheckSentAt() == null) {
            order.setReceivedCheckSentAt(clock.instant());
            orders.save(order);
        }
    }
}
