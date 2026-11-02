package com.nbh.backend.order.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Guest checkout is still supported alongside signed-in accounts (§4.7).
 * Branches were removed 2026-09-24 (founder decision — see HANDOFF.md); there
 * is one shop, so an order no longer names a fulfilment branch.
 */
@Getter
@Setter
@Entity
@Table(name = "customer_order")
public class CustomerOrder extends BaseEntity {

    @Column(name = "order_no", nullable = false, unique = true, length = 40)
    private String orderNo;

    /** Null for guest orders. Set when placed while signed in, or when an account's verified email adopts earlier guest orders. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_account_id")
    private com.nbh.backend.customer.entity.CustomerAccount customerAccount;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    /** Null for an order staff entered for a customer who gave no email (V70): such an order is simply not emailed. */
    @Column(name = "guest_email", length = 255)
    private String guestEmail;

    /** The member of staff who entered this order for a customer (V70); null for an order the customer placed. */
    @Column(name = "placed_by", length = 200)
    private String placedBy;

    @Column(name = "guest_phone", nullable = false, length = 20)
    private String guestPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_type", nullable = false, length = 20)
    private FulfilmentType fulfilmentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "vat_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal vatTotal;

    @Column(name = "delivery_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;

    /**
     * Who the rider should actually call at drop-off (V47) — distinct from {@link #guestPhone},
     * used for account contact/M-Pesa. Null means "same as the order phone" (checkout autofills it
     * there, but leaves it editable).
     */
    @Column(name = "delivery_contact_phone", length = 20)
    private String deliveryContactPhone;

    // ---- Optional extras about where a delivery goes and who gets it (V55). All null for a pickup and for orders that
    // gave none; which of them a delivery can hold depends on its method, see DeliveryDetails (the dto that moves them). ----

    @Column(name = "delivery_estate", length = 120)
    private String deliveryEstate;

    @Column(name = "delivery_street", length = 120)
    private String deliveryStreet;

    @Column(name = "delivery_building", length = 120)
    private String deliveryBuilding;

    /** Floor, door or house number. */
    @Column(name = "delivery_unit", length = 60)
    private String deliveryUnit;

    /** For a courier or sacco parcel: the office or branch it is collected from. */
    @Column(name = "delivery_courier_office", length = 120)
    private String deliveryCourierOffice;

    /** How to find the place, in the customer's own words (may run to several lines). */
    @Column(name = "delivery_directions", length = 400)
    private String deliveryDirections;

    /** Gate, security, "call when outside" and the like (may run to several lines). */
    @Column(name = "delivery_instructions", length = 400)
    private String deliveryInstructions;

    /** When someone other than the buyer receives it; the number to ring is {@link #deliveryContactPhone}. */
    @Column(name = "delivery_receiver_name", length = 100)
    private String deliveryReceiverName;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_time_window", length = 20)
    private DeliveryTimeWindow deliveryTimeWindow;

    /**
     * BODA, MATATU or COURIER for DELIVERY_COURIER orders — the customer's preferred manually-arranged
     * transport, all paid directly to the transporter and confirmed by phone (V33). Null for pickup and
     * for orders that pre-date V14. Historical VEHICLE values were migrated to MATATU by V33.
     */
    @Column(name = "delivery_method", length = 20)
    private String deliveryMethod;

    @Column(name = "placed_at")
    private Instant placedAt;

    /** When the one automatic "we still need your M-PESA code" email went out (V54); null until it has. */
    @Column(name = "payment_reminder_sent_at")
    private Instant paymentReminderSentAt;

    /** Who was actually assigned to carry a DELIVERY_COURIER order, and what they and the customer agreed on the phone (V35). Null until {@code OrderFulfilmentService.assignTransporter} runs; always null for PICKUP. */
    @Column(name = "transporter_name", length = 200)
    private String transporterName;

    @Column(name = "transporter_phone", length = 20)
    private String transporterPhone;

    @Column(name = "agreed_delivery_price", precision = 14, scale = 2)
    private BigDecimal agreedDeliveryPrice;

    /** Where a phone-arranged parcel was left (stage, office, counter) and the receipt/waybill number the transporter gave (V59). */
    @Column(name = "handoff_stage", length = 200)
    private String handoffStage;

    @Column(name = "handoff_reference", length = 60)
    private String handoffReference;

    @Column(name = "handoff_note", length = 300)
    private String handoffNote;

    /** When a member of staff stopped this order's new-order ring (V60); null while it is still ringing. */
    @Column(name = "alert_acknowledged_at")
    private Instant alertAcknowledgedAt;

    @Column(name = "alert_acknowledged_by", length = 120)
    private String alertAcknowledgedBy;

    /** The Metro operator the shopper chose and the likely fare they were shown, paid to the conductor (V82). */
    @Column(name = "transport_operator_id")
    private java.util.UUID transportOperatorId;

    @Column(name = "delivery_fare_min", precision = 10, scale = 2)
    private BigDecimal deliveryFareMin;

    @Column(name = "delivery_fare_max", precision = 10, scale = 2)
    private BigDecimal deliveryFareMax;

    /** What staff noted after calling the customer about paying ("called, will pay at 3pm"), who and when (V80). */
    @Column(name = "chase_note", length = 300)
    private String chaseNote;

    @Column(name = "chase_note_by", length = 120)
    private String chaseNoteBy;

    @Column(name = "chase_note_at")
    private Instant chaseNoteAt;

    /** The Terms of use and Privacy notice versions the shopper ticked at checkout, and when (V79); null for staff orders. */
    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    @Column(name = "privacy_version", length = 20)
    private String privacyVersion;

    @Column(name = "policies_accepted_at")
    private Instant policiesAcceptedAt;

    /** When staff told the shopper which transporter has it and where to collect; null until they have (V59). */
    @Column(name = "customer_informed_at")
    private Instant customerInformedAt;

    /** How the agreed delivery fee gets paid (V90); null until staff agree it with the customer. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_pay_way", length = 20)
    private DeliveryPayWay deliveryPayWay;

    @Column(name = "delivery_till", length = 40)
    private String deliveryTill;

    @Column(name = "delivery_terms_agreed_at")
    private Instant deliveryTermsAgreedAt;

    public enum DeliveryPayWay { ON_ARRIVAL, PAY_BUSINESS, PROVIDER_TILL }

    /** When the order left the shop, and when staff asked the customer whether it arrived (V91). */
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "received_check_sent_at")
    private Instant receivedCheckSentAt;

    /** In the order they were entered (V70): a package's own products follow the package's line, in the package's order. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC, id ASC")
    private List<CustomerOrderItem> items = new ArrayList<>();

    /** Developer-toggleable demo content (2026-09-29) — backs fabricated reviews/sale-events only,
     *  never a real order. See MockDataSeederService. */
    @Column(nullable = false)
    private boolean isMockData = false;
}
