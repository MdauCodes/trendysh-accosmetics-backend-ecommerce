package com.nbh.backend.order.service;

import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.order.dto.DeliveryTermsDtos.DeliveryMoneyRequest;
import com.nbh.backend.order.dto.DeliveryTermsDtos.DeliveryTermsRequest;
import com.nbh.backend.order.dto.DeliveryTermsDtos.DeliveryTermsView;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrder.DeliveryPayWay;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.BankPaymentSettings;
import com.nbh.backend.payment.service.PaymentInstructionsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The delivery fee staff agree with a customer on WhatsApp, and how it gets paid (owner, 2 Nov 2026). Only money paid to
 * the shop touches the order: the fee joins its total, so the pay page and the money trail say what is really owed.
 * An order already paid for its products owes the fee as a balance, which staff record when it arrives.
 */
@Service
public class OrderDeliveryTermsService {

    private static final Set<OrderStatus> AGREEABLE =
            EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, OrderStatus.PICKING, OrderStatus.READY);

    private final CustomerOrderRepository orders;
    private final PaymentRecordRepository payments;
    private final PaymentInstructionsService paymentInstructions;
    private final BankPaymentSettings bankSettings;
    private final Clock clock;

    public OrderDeliveryTermsService(CustomerOrderRepository orders, PaymentRecordRepository payments,
            PaymentInstructionsService paymentInstructions, BankPaymentSettings bankSettings, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.paymentInstructions = paymentInstructions;
        this.bankSettings = bankSettings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DeliveryTermsView view(UUID orderId) {
        return viewOf(orders.findById(orderId).orElseThrow(() -> new NotFoundException("No such order")));
    }

    @Transactional
    public DeliveryTermsView agree(UUID orderId, DeliveryTermsRequest request) {
        CustomerOrder order = orders.lockById(orderId).orElseThrow(() -> new NotFoundException("No such order"));
        if (order.getFulfilmentType() != FulfilmentType.DELIVERY_COURIER) {
            throw new UnprocessableException("Order " + order.getOrderNo() + " is a pickup order: there is no delivery to agree");
        }
        if (!AGREEABLE.contains(order.getStatus())) {
            throw new UnprocessableException("Order " + order.getOrderNo() + " is " + order.getStatus()
                    + ": its delivery can no longer be changed");
        }
        String till = request.till() == null ? "" : request.till().trim();
        if (request.payWay() == DeliveryPayWay.PROVIDER_TILL && till.isEmpty()) {
            throw new IllegalArgumentException("Enter the provider's till number");
        }
        if (feePaidToTheShop(order)) {
            throw new UnprocessableException("The delivery fee on order " + order.getOrderNo()
                    + " has already been paid to the shop, so it can no longer be changed");
        }

        BigDecimal newFee = request.payWay() == DeliveryPayWay.PAY_BUSINESS ? request.fee() : BigDecimal.ZERO;
        order.setGrandTotal(order.getGrandTotal().subtract(order.getDeliveryFee()).add(newFee));
        order.setDeliveryFee(newFee);
        order.setAgreedDeliveryPrice(request.fee());
        order.setDeliveryPayWay(request.payWay());
        order.setDeliveryTill(request.payWay() == DeliveryPayWay.PROVIDER_TILL ? till : null);
        order.setDeliveryTermsAgreedAt(clock.instant());
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            // The pay page and the code check both read the open payment's amount.
            payments.findByOrderId(orderId).stream().filter(OrderDeliveryTermsService::open)
                    .forEach(p -> p.setAmount(order.getGrandTotal()));
        }
        return viewOf(orders.save(order));
    }

    /** The delivery balance on an order already paid for its products has arrived at the shop. */
    @Transactional
    public DeliveryTermsView recordMoney(UUID orderId, DeliveryMoneyRequest request) {
        CustomerOrder order = orders.lockById(orderId).orElseThrow(() -> new NotFoundException("No such order"));
        BigDecimal owed = owed(order);
        if (owed.signum() == 0) {
            throw new UnprocessableException("Order " + order.getOrderNo() + " has no delivery money still to receive");
        }
        PaymentRecord record = new PaymentRecord();
        record.setOrder(order);
        record.setProvider("BANK");
        record.setMethod(PaymentRecord.PaymentMethod.BANK);
        record.setProviderReference(request.code().trim().toUpperCase());
        record.setAmount(owed);
        record.setStatus(PaymentRecord.PaymentStatus.SUCCESS);
        payments.save(record);
        return viewOf(order);
    }

    private DeliveryTermsView viewOf(CustomerOrder order) {
        String payRef = paymentInstructions.payReferenceFor(order);
        String paybill = bankSettings.paybillNumber();
        boolean hasPaybill = paybill != null && !paybill.isBlank();
        return new DeliveryTermsView(order.getDeliveryPayWay(), order.getDeliveryTill(), order.getDeliveryTermsAgreedAt(),
                order.getAgreedDeliveryPrice(), owed(order), payRef, hasPaybill ? paybill : null,
                hasPaybill ? bankSettings.paybillMerchantName() : null,
                hasPaybill ? bankSettings.paybillAccountFor(order.getCustomerName(), order.getGuestPhone(),
                        order.getOrderNo(), firstReference(order)) : null);
    }

    /** What is still to come to the shop for delivery: only ever on an order past its first payment. */
    private BigDecimal owed(CustomerOrder order) {
        if (order.getDeliveryPayWay() != DeliveryPayWay.PAY_BUSINESS || !paidOrLater(order)) {
            return BigDecimal.ZERO;
        }
        return order.getGrandTotal().subtract(received(order)).max(BigDecimal.ZERO);
    }

    private boolean feePaidToTheShop(CustomerOrder order) {
        return order.getDeliveryFee().signum() > 0 && paidOrLater(order) && owed(order).signum() == 0;
    }

    private static boolean paidOrLater(CustomerOrder order) {
        return order.getStatus() != OrderStatus.PENDING_PAYMENT && order.getStatus() != OrderStatus.EXPIRED
                && order.getStatus() != OrderStatus.CANCELLED;
    }

    private BigDecimal received(CustomerOrder order) {
        return payments.findByOrderId(order.getId()).stream()
                .filter(p -> p.getStatus() == PaymentRecord.PaymentStatus.SUCCESS)
                .map(PaymentRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String firstReference(CustomerOrder order) {
        List<PaymentRecord> records = payments.findByOrderId(order.getId());
        return records.stream().map(PaymentRecord::getPaymentReference).filter(r -> r != null).findFirst().orElse(null);
    }

    private static boolean open(PaymentRecord payment) {
        return payment.getStatus() == PaymentRecord.PaymentStatus.INITIATED
                || payment.getStatus() == PaymentRecord.PaymentStatus.PENDING;
    }
}
