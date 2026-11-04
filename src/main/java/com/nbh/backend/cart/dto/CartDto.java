package com.nbh.backend.cart.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code freeAdded}: free pieces the shop added on THIS request under a buy-X-get-Y deal (owner, 2026-11-04: "the 3 are
 * added on top with a toast message"), so the shop can say so once; empty otherwise.
 */
public record CartDto(
        UUID id,
        String sessionId,
        List<CartItemDto> items,
        BigDecimal subtotal,
        List<FreeAdded> freeAdded
) {
    /** {@code qty} free pieces of {@code productName} added under the campaign {@code campaignTitle}. */
    public record FreeAdded(UUID dealId, String productName, BigDecimal qty, String campaignTitle) {}

    public static CartDto from(com.nbh.backend.cart.entity.Cart cart, List<CartItemDto> items) {
        return from(cart, items, List.of());
    }

    public static CartDto from(com.nbh.backend.cart.entity.Cart cart, List<CartItemDto> items, List<FreeAdded> freeAdded) {
        BigDecimal subtotal = items.stream().map(CartItemDto::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartDto(cart.getId(), cart.getSessionId(), items, subtotal, freeAdded);
    }
}
