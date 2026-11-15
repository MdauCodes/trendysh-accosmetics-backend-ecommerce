package com.nbh.backend.live;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * A hint pushed to staff dashboards over the live stream: what happened and, where there is one, which
 * order and how much. It carries no customer details and is only ever a prompt to look again: every screen
 * re-reads the real data from the API, so a missed or duplicated hint can never leave a dashboard wrong for
 * longer than its next refresh.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminEvent(Type type, String orderNo, BigDecimal amount, String topic, String payerName) {

    public AdminEvent(Type type, String orderNo, BigDecimal amount) {
        this(type, orderNo, amount, null, null);
    }

    /**
     * A customer says they have paid: the name they say the M-PESA payment came from rides along, so the toast can say
     * "Njeri Kamau paid for their order of KSh 1,500" (owner, 15 Nov 2026). Staff dashboards only: the stream needs a
     * staff sign-in.
     */
    public static AdminEvent paymentClaimed(String orderNo, BigDecimal amount, String payerName) {
        return new AdminEvent(Type.PAYMENT_CLAIMED, orderNo, amount, null, payerName);
    }


    public enum Type {
        /** A customer placed an order (now waiting for its Paybill payment). */
        ORDER_PLACED,
        /** An order moved on: picking, ready, dispatched, delivered or cancelled. */
        ORDER_UPDATED,
        /** A payment was confirmed, by staff or a bank credit. */
        PAYMENT_CONFIRMED,
        PAYMENT_FAILED,
        /** A customer says they've paid; staff have to check it against the statement. */
        PAYMENT_CLAIMED,
        /** Money arrived at the bank account (matched to an order or not). */
        BANK_CREDIT,
        /** A shared collection changed; {@code topic} says which (see DataChangedEvent.Topic). */
        DATA_CHANGED
    }

    public static AdminEvent dataChanged(String topic) {
        return new AdminEvent(Type.DATA_CHANGED, null, null, topic, null);
    }

    public static AdminEvent of(Type type, String orderNo) {
        return new AdminEvent(type, orderNo, null);
    }
}
