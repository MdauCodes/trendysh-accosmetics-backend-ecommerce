package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.CustomerOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderDto(
        UUID id,
        String orderNo,
        String status,
        String fulfilmentType,
        BigDecimal subtotal,
        BigDecimal vatTotal,
        BigDecimal deliveryFee,
        BigDecimal grandTotal,
        List<OrderItemDto> items,
        java.time.Instant createdAt,
        String transporterName,
        String transporterPhone,
        BigDecimal agreedDeliveryPrice,
        /** For a pickup order, which shop it waits at (the label the shopper chose at checkout); null for a delivery, whose address stays out of this view. */
        String pickupPlace,
        /** Who carries it, in a few words: a rider, or the provider chosen at checkout. Not the address. */
        String deliveryCarrier,
        /**
         * Only ever filled in for the order's own owner (a signed-in account, or a guest who proved their email) and only
         * while the order is still waiting on money: what opens the pay page, where the M-PESA code that completes the
         * order is entered. Null everywhere else, including the checkout response (which carries its own instructions).
         */
        String payRef
) {
    public static OrderDto from(CustomerOrder order) {
        return new OrderDto(
                order.getId(), order.getOrderNo(), order.getStatus().name(), order.getFulfilmentType().name(),
                order.getSubtotal(), order.getVatTotal(),
                order.getDeliveryFee(), order.getGrandTotal(),
                order.getItems().stream().map(OrderItemDto::from).toList(),
                order.getCreatedAt(),
                order.getTransporterName(), order.getTransporterPhone(), order.getAgreedDeliveryPrice(),
                
                order.getFulfilmentType() == com.nbh.backend.order.entity.FulfilmentType.PICKUP
                        ? order.getDeliveryAddress() : null,
                carrierOf(order),
                null);
    }

    private static String carrierOf(CustomerOrder order) {
        if ("BODA".equals(order.getDeliveryMethod())) return "A rider";
        String address = order.getDeliveryAddress();
        int dash = address == null ? -1 : address.indexOf(" — ");
        return dash > 0 ? address.substring(0, dash).trim() : null;
    }

    public OrderDto withPayRef(String reference) {
        return new OrderDto(id, orderNo, status, fulfilmentType, subtotal, vatTotal, deliveryFee, grandTotal, items,
                createdAt, transporterName, transporterPhone, agreedDeliveryPrice, pickupPlace, deliveryCarrier, reference);
    }
}
