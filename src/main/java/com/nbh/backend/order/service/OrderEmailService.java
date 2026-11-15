package com.nbh.backend.order.service;

import com.nbh.backend.common.email.EmailTemplates;
import com.nbh.backend.common.email.EmailTemplates.Fact;
import com.nbh.backend.common.email.EmailTemplates.Line;
import com.nbh.backend.common.event.CustomerEmailRequestedEvent;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.dto.PaymentInstructionsDto;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.PaymentInstructionsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The emails about an order (owner's decision, 2026-10-06): one when it is placed, with the way to pay and the link back
 * to the page that takes the M-PESA payment name; one reminder if that code has not arrived after a while; and a receipt when
 * the payment is confirmed. Before this the shop sent no order emails at all.
 *
 * <p>An email is requested as a {@link CustomerEmailRequestedEvent}, so it only goes out once the transaction that
 * asked for it has committed: nobody is emailed about an order that then failed to save. Without a mail provider
 * configured (no {@code BREVO_API_KEY}) it is only written to the log, as for every other email the shop sends.
 *
 * <p>The placed and received emails run in a transaction of their own ({@code REQUIRES_NEW}): they are triggered after the
 * order's transaction has committed, from its events, and read the order afresh.
 */
@Service
public class OrderEmailService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(SHOP_ZONE);

    private final CustomerOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentInstructionsService instructionsService;
    private final ApplicationEventPublisher events;
    private final String frontendUrl;

    public OrderEmailService(CustomerOrderRepository orderRepository,
                             PaymentRecordRepository paymentRecordRepository,
                             PaymentInstructionsService instructionsService,
                             ApplicationEventPublisher events,
                             @Value("${app.frontend-url:http://localhost:5250}") String frontendUrl) {
        this.orderRepository = orderRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.instructionsService = instructionsService;
        this.events = events;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /** "Your order is saved, one more step": the way to pay and the link to type the M-PESA payment name. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void orderPlaced(UUID orderId) {
        CustomerOrder order = orderRepository.findById(orderId).orElse(null);
        PaymentRecord payment = unpaidBankPayment(orderId);
        if (order == null || payment == null || !emailable(order)) {
            return;
        }
        PaymentInstructionsDto instructions = instructionsService.build(payment);
        String firstName = firstName(order);
        String until = payment.getExpiresAt() == null ? null : CLOCK.format(payment.getExpiresAt());

        // Owner, 15 Nov 2026: the order is received; paying is what gets it prepared. No "not complete", no extra step.
        // Owner, 15 Nov 2026: the order is already being prepared; it leaves (or can be collected) once the payment is in.
        String intro = "Thank you for ordering from Trendysh. We've received your order and are already preparing it. "
                + (order.getFulfilmentType() == FulfilmentType.PICKUP
                        ? "It is ready to collect as soon as your payment is confirmed. "
                        : "We dispatch it as soon as your payment is confirmed. ")
                + "Pay with M-PESA Paybill using the details below"
                + (until == null ? "." : " by " + until + ".");
        List<Fact> facts = payFacts(instructions);
        String payLink = payLink(order, payment);

        send(order, "Order " + order.getOrderNo() + " received: pay with M-PESA to get it prepared",
                text(firstName, "We've received your order", intro, facts, order, "Follow your order and tell us once you've paid:", payLink,
                        "Questions? Call or WhatsApp us on " + instructions.supportWhatsapp() + "."),
                EmailTemplates.orderMessage(logoUrl(), firstName, "We've received your order", intro, facts,
                        itemLines(order), "Total", ksh(order.getGrandTotal()), "See my order", payLink,
                        "Questions? Call or WhatsApp us on " + instructions.supportWhatsapp() + "."));
    }

    /**
     * The one reminder: the customer placed an order, has not entered the M-PESA payment name that completes it, and the stock
     * is still held for them. Called inside the reminder job's own transaction, with the order and payment it just locked.
     */
    public void paymentReminder(CustomerOrder order, PaymentRecord payment) {
        if (!emailable(order)) {
            return;
        }
        PaymentInstructionsDto instructions = instructionsService.build(payment);
        String firstName = firstName(order);
        String until = payment.getExpiresAt() == null ? null : CLOCK.format(payment.getExpiresAt());

        String intro = "We're preparing your order " + order.getOrderNo() + ", and it goes out as soon as your payment is confirmed. "
                + (until == null ? "" : "We're holding your items until " + until + ". ")
                + "Pay with M-PESA Paybill using the details below. Already paid? Open your order and press \"I have paid\".";
        List<Fact> facts = payFacts(instructions);
        String payLink = payLink(order, payment);

        send(order, "Order " + order.getOrderNo() + ": your items are waiting for you",
                text(firstName, "Your order is waiting for its payment", intro, facts, order, "Open your order:", payLink,
                        "Need help? Call or WhatsApp us on " + instructions.supportWhatsapp() + "."),
                EmailTemplates.orderMessage(logoUrl(), firstName, "Your order is waiting for its payment", intro, facts,
                        itemLines(order), "Total", ksh(order.getGrandTotal()), "Open my order", payLink,
                        "Need help? Call or WhatsApp us on " + instructions.supportWhatsapp() + "."));
    }

    /** "Payment received": the receipt, with where the order is collected or how it will be delivered. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void paymentReceived(UUID orderId) {
        CustomerOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || !emailable(order)) {
            return;
        }
        String firstName = firstName(order);
        String next = order.getFulfilmentType() == FulfilmentType.PICKUP
                ? "We're getting it ready and will update your order page when it can be collected"
                        + (order.getDeliveryAddress() == null ? "." : " at " + order.getDeliveryAddress() + ".")
                : "We're getting it ready and will call you to arrange the delivery.";
        String intro = "Thank you! We've received your payment of " + ksh(order.getGrandTotal()) + " for order "
                + order.getOrderNo() + ". " + next;
        String trackLink = frontendUrl + "/track?order=" + order.getOrderNo();

        send(order, "Payment received for order " + order.getOrderNo(),
                text(firstName, "Payment received", intro, List.of(), order, "Follow your order here:", trackLink, null),
                EmailTemplates.orderMessage(logoUrl(), firstName, "Payment received", intro, List.of(), itemLines(order),
                        "Total paid", ksh(order.getGrandTotal()), "Track my order", trackLink, null));
    }

    // ---- building blocks

    private PaymentRecord unpaidBankPayment(UUID orderId) {
        return paymentRecordRepository.findByOrderId(orderId).stream()
                .filter(p -> p.getPaymentReference() != null
                        && (p.getStatus() == PaymentRecord.PaymentStatus.INITIATED
                            || p.getStatus() == PaymentRecord.PaymentStatus.PENDING))
                .findFirst().orElse(null);
    }

    /** Demo-catalogue orders never get email, and an order with no address to send to has nobody to tell. */
    private static boolean emailable(CustomerOrder order) {
        return !order.isMockData() && order.getGuestEmail() != null && !order.getGuestEmail().isBlank();
    }

    private static List<Fact> payFacts(PaymentInstructionsDto instructions) {
        List<Fact> facts = new ArrayList<>();
        if (instructions.paybill() != null) {
            facts.add(new Fact("Business number", instructions.paybill().paybillNumber()));
            facts.add(new Fact("Account number", instructions.paybill().accountNumber()));
            facts.add(new Fact("Amount", ksh(instructions.amount())));
        }
        return facts;
    }

    /** The order's own tracking page, with the reference that shows its Paybill details and "I have paid". */
    private String payLink(CustomerOrder order, PaymentRecord payment) {
        return frontendUrl + "/track?order=" + order.getOrderNo() + "&ref=" + payment.getPaymentReference();
    }

    private String logoUrl() {
        return frontendUrl + "/trendysh-logo.png";
    }

    /** What was ordered, one line each (a package's own components are part of its line, so they are not listed again). */
    private static List<Line> itemLines(CustomerOrder order) {
        List<Line> lines = new ArrayList<>();
        for (CustomerOrderItem item : order.getItems()) {
            if (item.getParentItem() != null) {
                continue;
            }
            // The name the line was bought under: renaming the variant afterwards must not change what the email says.
            String name = item.soldLabel()
                    + " × " + item.getQty().stripTrailingZeros().toPlainString();
            lines.add(new Line(name, ksh(item.getLineTotal())));
        }
        if (order.getDeliveryFee() != null && order.getDeliveryFee().signum() > 0) {
            lines.add(new Line("Delivery", ksh(order.getDeliveryFee())));
        }
        return lines;
    }

    /** The plain-text version every email carries alongside its HTML. */
    private String text(String firstName, String headline, String intro, List<Fact> facts, CustomerOrder order,
                        String linkLead, String link, String note) {
        StringBuilder text = new StringBuilder("Hi ").append(firstName).append(",\n\n")
                .append(headline).append("\n\n").append(intro).append("\n\n");
        for (Fact fact : facts) {
            text.append(fact.label()).append(": ").append(fact.value()).append("\n");
        }
        if (!facts.isEmpty()) {
            text.append("\n");
        }
        for (Line line : itemLines(order)) {
            text.append("  ").append(line.description()).append("  ").append(line.amountText()).append("\n");
        }
        text.append("  Total  ").append(ksh(order.getGrandTotal())).append("\n\n")
                .append(linkLead).append("\n").append(link).append("\n");
        if (note != null) {
            text.append("\n").append(note).append("\n");
        }
        return text.append("\nTrendysh Accosmetic").toString();
    }

    private void send(CustomerOrder order, String subject, String textBody, String htmlBody) {
        events.publishEvent(new CustomerEmailRequestedEvent(order.getGuestEmail(), subject, textBody, htmlBody));
    }

    /**
     * A name a person could have: letters, digits, apostrophes and hyphens, at most 30, with at least one letter. The
     * checkout is public and the name is whatever the sender typed, so a "first name" like www.refund-example.co.ke
     * would otherwise open an email from the shop's own address and be turned into a link by the reader's mail app, and
     * a phone number typed into the name box would read "Thank you, 0712345678". Anything else greets as "there".
     */
    private static final Pattern NAME_LIKE = Pattern.compile("(?=.*\\p{L})[\\p{L}\\p{M}\\p{N}'’-]{1,30}");

    private static String firstName(CustomerOrder order) {
        String first = firstNameOf(order.getCustomerName());
        return first == null ? "there" : first;
    }

    /**
     * The first word of a customer's name when it looks like a name (the checkout is public, so it is whatever was
     * typed: a web address is never used as a greeting); null otherwise.
     */
    public static String firstNameOf(String customerName) {
        String name = customerName == null ? "" : customerName.trim();
        if (name.isEmpty()) {
            return null;
        }
        String first = name.split("\\s+")[0];
        return NAME_LIKE.matcher(first).matches() ? first : null;
    }

    /** "KSh 2,400", or "KSh 2,400.50" when there are cents. */
    static String ksh(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        boolean whole = value.stripTrailingZeros().scale() <= 0;
        return "KSh " + new DecimalFormat(whole ? "#,##0" : "#,##0.00").format(value);
    }

    /** When the stock hold ends, as a clock time in the shop's zone; used by tests. */
    static String clock(Instant instant) {
        return CLOCK.format(instant);
    }
}
