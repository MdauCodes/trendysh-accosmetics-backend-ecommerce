package com.nbh.backend.order.dto;

import java.util.UUID;

/** {@code paymentInstructions} is what the pay page shows: the paybill and bank details for this order. */
public record CheckoutResponseDto(OrderDto order, UUID paymentRecordId,
                                  com.nbh.backend.payment.dto.PaymentInstructionsDto paymentInstructions) {}
