package com.nbh.backend.order.entity;

/**
 * The team arranges any rider/vehicle transport manually and the customer pays
 * that courier directly, so there is one courier delivery path. See
 * {@code V12__align_delivery_model_with_business_decision.sql}.
 *
 * <p>{@code BRANCH_PICKUP} was renamed {@code PICKUP} 2026-09-24 when branches
 * were removed (founder decision — see HANDOFF.md): there is one shop, so
 * "branch" pickup is just pickup. {@code V26__single_stock_account.sql}
 * migrates the stored value on existing orders.
 */
public enum FulfilmentType {
    DELIVERY_COURIER,
    PICKUP
}
