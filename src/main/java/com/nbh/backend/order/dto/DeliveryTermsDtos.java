package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.CustomerOrder.DeliveryPayWay;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** The delivery fee staff agree with a customer, and how it gets paid. See OrderDeliveryTermsService. */
public final class DeliveryTermsDtos {

    private DeliveryTermsDtos() {}

    public record DeliveryTermsRequest(
            @NotNull @DecimalMin("0") BigDecimal fee,
            @NotNull DeliveryPayWay payWay,
            @Size(max = 40) String till
    ) {}

    public record DeliveryMoneyRequest(@NotBlank @Size(max = 60) String code) {}

    /** What the order page needs to show the terms and write the customer's message. */
    public record DeliveryTermsView(
            DeliveryPayWay payWay,
            String till,
            Instant agreedAt,
            BigDecimal fee,
            /** Delivery money still to reach the shop on an order already paid for its products. */
            BigDecimal owed,
            /** The pay-page reference while the order is waiting on money; null after that. */
            String payRef,
            String paybillNumber,
            String paybillMerchantName,
            String paybillAccount
    ) {}
}
