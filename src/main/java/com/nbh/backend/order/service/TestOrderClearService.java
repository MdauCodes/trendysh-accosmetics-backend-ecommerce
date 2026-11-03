package com.nbh.backend.order.service;

import com.nbh.backend.order.dto.OrderReturnRequest;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.stock.service.StockReservationService;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Clears the test orders from before the launch (owner, 3 Nov 2026), once, when the developer presses it. Only orders from
 * before a fixed moment can be touched, so a real order placed later is safe whatever is pressed. Stock that left on them
 * comes back through a customer return, so the stock ledger stays true; the return's record outlives the order.
 */
@Service
public class TestOrderClearService {

    private static final Set<OrderStatus> STOCK_LEFT = EnumSet.of(OrderStatus.DISPATCHED, OrderStatus.DELIVERED);

    public record TestOrder(UUID id, String orderNo, String customerName, String status, BigDecimal grandTotal,
                            Instant createdAt, boolean demo) {}

    public record Cleared(int orders, int stockReturned) {}

    private final CustomerOrderRepository orders;
    private final OrderReturnService returns;
    private final StockReservationService reservations;
    private final EntityManager em;
    private final Instant before;

    public TestOrderClearService(CustomerOrderRepository orders, OrderReturnService returns,
            StockReservationService reservations, EntityManager em,
            @Value("${app.orders.clear-test-orders-before:2026-11-03T01:40:00Z}") Instant before) {
        this.orders = orders;
        this.returns = returns;
        this.reservations = reservations;
        this.em = em;
        this.before = before;
    }

    @Transactional(readOnly = true)
    public List<TestOrder> waiting() {
        return orders.createdBefore(before).stream()
                .map(o -> new TestOrder(o.getId(), o.getOrderNo(), o.getCustomerName(), o.getStatus().name(),
                        o.getGrandTotal(), o.getCreatedAt(), o.isMockData()))
                .toList();
    }

    @Transactional
    public Cleared clearAll() {
        return clear(orders.createdBefore(before).stream().map(CustomerOrder::getId).toList());
    }

    /** All or nothing: one transaction, so a failure leaves every order and every shelf count as it was. */
    @Transactional
    public Cleared clear(List<UUID> ids) {
        int stockReturned = 0;
        for (UUID id : ids) {
            CustomerOrder order = orders.findById(id).orElseThrow();
            if (STOCK_LEFT.contains(order.getStatus()) && !order.isMockData()) {
                List<OrderReturnRequest.Line> lines = new ArrayList<>();
                for (CustomerOrderItem item : order.getItems()) {
                    // A line with no reservation took nothing off the shelf: a package's own line, or uncounted stock.
                    if (item.getReservation() == null) continue;
                    BigDecimal outstanding = item.getQty().subtract(item.getReturnedQty());
                    if (outstanding.signum() > 0) {
                        lines.add(new OrderReturnRequest.Line(item.getId(), outstanding.toPlainString(), true));
                    }
                }
                if (!lines.isEmpty()) {
                    returns.post(id, new OrderReturnRequest(lines, "Test order cleared before launch", null));
                    stockReturned++;
                }
            }
            reservations.releaseActiveFor("CUSTOMER_ORDER", id);
        }
        em.flush();
        em.clear();
        for (UUID id : ids) {
            delete("DELETE FROM payment_claim WHERE payment_record_id IN (SELECT id FROM payment_record WHERE order_id = :id)", id);
            delete("DELETE FROM bank_credit WHERE payment_record_id IN (SELECT id FROM payment_record WHERE order_id = :id)", id);
            delete("DELETE FROM payment_record WHERE order_id = :id", id);
            delete("DELETE FROM order_price_change WHERE order_id = :id", id);
            delete("DELETE FROM product_review WHERE order_id = :id", id);
            delete("DELETE FROM product_sale_event WHERE order_id = :id", id);
            delete("DELETE FROM customer_order_item WHERE order_id = :id", id);
            delete("DELETE FROM customer_order WHERE id = :id", id);
        }
        return new Cleared(ids.size(), stockReturned);
    }

    private void delete(String sql, UUID id) {
        em.createNativeQuery(sql).setParameter("id", id).executeUpdate();
    }
}
