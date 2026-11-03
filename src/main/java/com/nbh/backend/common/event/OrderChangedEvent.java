package com.nbh.backend.common.event;

/**
 * Something about an order changed that has no event of its own: its delivery fee, its transporter, an expiry, a turned-down
 * payment. Delivered after commit as a live "look again" hint, so screens update only when there is something new.
 *
 * @param tellCustomer whether the customer's own tracking or pay page should look again too
 */
public record OrderChangedEvent(String orderNo, boolean tellCustomer) {}
