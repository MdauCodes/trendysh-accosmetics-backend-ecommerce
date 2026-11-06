package com.nbh.backend.order.service;

import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.service.VariantCovers;
import com.nbh.backend.common.event.OrderPlacedEvent;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.TooManyRequestsException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.packages.service.PackageService;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.dto.CheckoutRequest;
import com.nbh.backend.order.dto.DeliveryDetails;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.BankPaymentSettings;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
import com.nbh.backend.stock.service.StockReservationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Turns a cart into a PENDING_PAYMENT order, reserving stock for every line
 * in the SAME transaction the order is created in — if any line can't be
 * reserved (insufficient availability), the whole checkout rolls back
 * atomically, including the order itself. This is the point in the system
 * where the founder's confirmed 15-minute checkout-time hold actually starts
 * (architecture doc §4.5's lifecycle table: "Begin checkout / M-Pesa STK
 * sent -> ACTIVE, ONLINE_CART, expires_at = now()+15min").
 *
 * <p>Branches were removed 2026-09-24 (founder decision — see HANDOFF.md).
 * This service used to try the main branch first and fall back to another
 * active branch if it was short, then reserve there — a real defect, since
 * {@code OrderFulfilmentService.fulfil} always dispatched from the order's
 * nominal fulfilment branch regardless of which branch the reservation
 * actually landed on. There is now exactly one place to reserve from (SHOP),
 * so that class of bug is gone, not just fixed for today's data.
 *
 * Selling prices are treated as VAT-inclusive (the Kenyan retail norm — the
 * shelf/display price already includes VAT), so vatTotal here is the VAT
 * COMPONENT of the subtotal, extracted for reporting, not an amount added on
 * top; grandTotal = subtotal + deliveryFee.
 */
@Service
public class CheckoutService {

    private static final int REFERENCE_ATTEMPTS = 5;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CustomerOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final StockReservationService reservationService;
    /** Optional so the unit tests can build this service by hand (then every option is tracked). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.stock.service.StockTrackingService stockTracking;

    /** For "sell regardless of stock" (V101). Optional so hand-built tests need not provide it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.stock.service.ShopperStock shopperStock;

    private boolean sellsRegardlessOfStock() {
        return shopperStock != null && shopperStock.sellingRegardless();
    }
    /** Optional for the same reason: a shopper refused for stock is written down for staff (V81). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.stock.service.StockShortfallService shortfalls;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.stock.service.StockAvailabilityService availability;
    /** Optional for the same reason: Metro operators and their drop-off locations (V82). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.delivery.transport.TransportService transport;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.customer.repository.CustomerAccountRepository customerAccounts;
    /** Optional for the same reason: campaign deals (V96), so a line sold under one is counted against its cap. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.catalog.service.LiveDeals liveDeals;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CheckoutService.class);
    private final OrderNoGenerator orderNoGenerator;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentReferenceGenerator referenceGenerator;
    private final BankPaymentSettings paymentSettings;
    private final int maxOpenOrdersPerContact;
    private final PackageService packageService;
    private final VariantCovers variantCovers;

    public CheckoutService(CartRepository cartRepository,
                            CartItemRepository cartItemRepository,
                            CustomerOrderRepository orderRepository,
                            PaymentRecordRepository paymentRecordRepository,
                            StockReservationService reservationService,
                            OrderNoGenerator orderNoGenerator,
                            Clock clock,
                            ApplicationEventPublisher eventPublisher,
                            PaymentReferenceGenerator referenceGenerator,
                            BankPaymentSettings paymentSettings,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${app.checkout.max-open-orders-per-contact:3}") int maxOpenOrdersPerContact,
                            PackageService packageService,
                            VariantCovers variantCovers) {
        this.maxOpenOrdersPerContact = maxOpenOrdersPerContact;
        this.packageService = packageService;
        this.variantCovers = variantCovers;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.reservationService = reservationService;
        this.orderNoGenerator = orderNoGenerator;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.referenceGenerator = referenceGenerator;
        this.paymentSettings = paymentSettings;
    }

    @Transactional
    public CheckoutResult checkout(String sessionId, CheckoutRequest request) {
        return checkout(sessionId, request, null);
    }

    /** {@code customer} is the signed-in shopper, or null for a guest checkout. */
    @Transactional
    public CheckoutResult checkout(String sessionId, CheckoutRequest request,
                                    com.nbh.backend.customer.entity.CustomerAccount customer) {
        return checkout(sessionId, request, customer, Placement.SHOPPER);
    }

    /**
     * Who is placing the order: the shopper themselves at the checkout, or a member of staff entering it for a customer
     * ({@code StaffOrderService}). Staff are not held to the shopper's limit on orders waiting for payment (a limit that
     * exists so the public form cannot tie up the shelf), the order records who entered it, and it does not ring the
     * new-order alert for the person who just placed it.
     */
    public record Placement(String staffName, String staffEmail) {
        public static final Placement SHOPPER = new Placement(null, null);

        public boolean byStaff() {
            return staffName != null;
        }
    }

    @Transactional
    public CheckoutResult checkout(String sessionId, CheckoutRequest request,
                                    com.nbh.backend.customer.entity.CustomerAccount customer, Placement placement) {
        requireUsableContactDetails(request);
        Cart cart = cartRepository.findWithLockBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("No cart for this session"));
        if (cart.getStatus() != Cart.CartStatus.ACTIVE) {
            throw new ConflictException("This cart has already been checked out");
        }
        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId()).stream()
                .sorted(CartItem.IN_ORDER_ADDED).toList();
        if (cartItems.isEmpty()) {
            throw new IllegalStateException("Cannot check out an empty cart");
        }
        // Demo-catalogue products are never for sale (see CartService.isPurchasable). The controller already
        // refuses them while pricing the basket; this is the same rule where the order is actually created, so
        // no caller of this method can ever turn a demo product into a real order and a real payment.
        for (CartItem cartItem : cartItems) {
            ProductVariant variant = cartItem.getVariant();
            if (variant != null && variant.getProduct() != null && variant.getProduct().isMockData()) {
                throw new UnprocessableException(
                        com.nbh.backend.cart.service.CartService.demoProductMessage(variant.getProduct().getName())
                                + " Please remove it from your cart and try again.");
            }
        }
        // Every checkout holds stock for the whole payment window, and the public endpoint has no
        // login, so without a limit one person could tie up the shelf with orders they never pay
        // for. Counting by phone OR email (when there is one) means changing just one doesn't get round it;
        // OrderExpiryService is what frees a slot again once a hold lapses.
        String contactPhone = PhoneNumbers.normalize(request.guestPhone());
        long open = placement.byStaff() ? 0 : orderRepository.countByStatusForContact(
                OrderStatus.PENDING_PAYMENT, contactPhone, request.guestEmail() == null ? "" : request.guestEmail());
        if (open >= maxOpenOrdersPerContact) {
            throw new TooManyRequestsException("You already have " + open
                    + " orders waiting for payment. Please pay for one of them, or wait for it to expire,"
                    + " before placing another.");
        }

        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(orderNoGenerator.next());
        order.setCustomerAccount(customer);
        order.setCustomerName(request.customerName().trim());
        // Optional, on the public form and on an order staff enter: without one it is stored with none.
        order.setGuestEmail(request.guestEmail() == null || request.guestEmail().isBlank()
                ? null : request.guestEmail().trim());
        order.setGuestPhone(contactPhone);
        if (placement.byStaff()) {
            order.setPlacedBy(placement.staffName());
            // The person who entered it needs no ring to tell them an order has arrived.
            order.setAlertAcknowledgedAt(Instant.now(clock));
            order.setAlertAcknowledgedBy(placement.staffEmail());
        }
        // What the shopper agreed to with the two boxes at checkout. Staff orders go through a form without them.
        if (!placement.byStaff() && (request.termsVersion() != null || request.privacyVersion() != null)) {
            order.setTermsVersion(request.termsVersion());
            order.setPrivacyVersion(request.privacyVersion());
            order.setPoliciesAcceptedAt(Instant.now(clock));
        }
        order.setFulfilmentType(request.fulfilmentType());
        order.setDeliveryAddress(request.deliveryAddress());
        // Blank/same-as-main-phone is stored as null: null means "ring the order phone".
        String deliveryContact = request.deliveryContactPhone() == null || request.deliveryContactPhone().isBlank()
                ? null
                : PhoneNumbers.normalize(request.deliveryContactPhone());
        order.setDeliveryContactPhone(
                deliveryContact != null && deliveryContact.equals(contactPhone) ? null : deliveryContact);
        // Pickup has no courier leg, so a stray method on a pickup order is dropped, not stored.
        order.setDeliveryMethod(request.fulfilmentType() == FulfilmentType.DELIVERY_COURIER ? request.deliveryMethod() : null);
        // The optional extras (where exactly, how to find it, who receives it, when) only mean something for a delivery,
        // and only the ones that suit how it travels are kept; a pickup or an order that gave none stores none.
        DeliveryDetails.store(
                request.fulfilmentType() == FulfilmentType.DELIVERY_COURIER && request.deliveryDetails() != null
                        ? request.deliveryDetails().cleanedFor(request.deliveryMethod())
                        : null,
                order);
        // A Metro (V82): the operator chosen and where it is dropped, tidied and kept for the next shopper of that
        // operator, with the likely fare the shopper was shown (paid to the conductor on delivery).
        if (transport != null && request.transport() != null && request.transport().stopId() == null
                && request.fulfilmentType() == FulfilmentType.DELIVERY_COURIER && "MATATU".equals(request.deliveryMethod())) {
            var resolved = transport.resolveForOrder(request.transport().operatorId(), request.transport().stop());
            if (resolved != null) {
                order.setTransportOperatorId(resolved.operatorId());
                order.setDeliveryFareMin(resolved.fareMin());
                order.setDeliveryFareMax(resolved.fareMax());
                if (resolved.stopName() != null) {
                    order.setDeliveryAddress(resolved.operatorName() + " — " + resolved.stopName());
                }
            }
        }
        // A signed-in shopper's next order starts from this one's delivery (owner, 2026-10-28).
        if (customer != null && !placement.byStaff() && customerAccounts != null
                && request.deliveryProfile() != null && !request.deliveryProfile().isBlank()) {
            customerAccounts.saveDelivery(customer.getId(), request.deliveryProfile());
        }
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(BigDecimal.ZERO);
        order.setVatTotal(BigDecimal.ZERO);
        order.setDeliveryFee(BigDecimal.ZERO);
        // A place chosen under a provider has a price, paid with the order before delivery (owner, 2 Nov 2026). Without
        // one, staff quote it on the order page.
        if (transport != null && request.transport() != null && request.transport().stopId() != null
                && request.fulfilmentType() == FulfilmentType.DELIVERY_COURIER) {
            var priced = transport.priceForOrder(request.transport().operatorId(), request.transport().stopId());
            if (priced != null) {
                order.setTransportOperatorId(priced.operatorId());
                order.setDeliveryFee(priced.price());
                order.setAgreedDeliveryPrice(priced.price());
                order.setDeliveryPayWay(CustomerOrder.DeliveryPayWay.PAY_BUSINESS);
                order.setDeliveryTermsAgreedAt(java.time.Instant.now());
            }
        }
        order.setGrandTotal(order.getDeliveryFee());
        order = orderRepository.save(order); // needs an id before reservations can reference it

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;
        // The stock hold and the payment window are the same instant, always.
        Instant expiresAt = Instant.now(clock).plus(paymentSettings.paymentWindow());

        // Phase 0 fix (2026-09-27), D1, extended for packages: reserve in a deterministic order (the
        // REAL variant id ASC) — the same order StockPostingService.post already locks its balance
        // rows in. A package line expands into one reservation target per real component here, so this
        // sorts by the variant actually reserved rather than the cart line's own (stockless) package
        // variant — without it, two concurrent checkouts that touch the same underlying SKUs
        // (directly, or buried inside different packages) in opposite orders could still deadlock in
        // Postgres, which is exactly what the original fix was for.
        // position: where a package's product stands in its package, so the order can list the products in the package's own order
        // although they are reserved in variant-id order.
        record ReservationTarget(CartItem cartItem, ProductVariant variant, BigDecimal qty, boolean isPackageComponent,
                                 int position) {}
        List<ReservationTarget> targets = new java.util.ArrayList<>();
        for (CartItem cartItem : cartItems) {
            // A package expands into its products as the shopper picked them (another colour or size of a product inside it):
            // the variants picked are what is reserved and what the order lines name.
            PackageService.Selection selection = packageService.selection(cartItem.getVariant().getId(), cartItem.getChoices());
            if (selection.outOfDate()) {
                // Staff stopped a product being changed, or the colour or size is gone, since this was picked: never
                // pack another one in its place without the shopper knowing.
                throw new UnprocessableException(
                        com.nbh.backend.cart.service.CartService.outOfDatePackageMessage(cartItem.getProductNameSnapshot()));
            }
            List<PackageService.SelectedPart> components = selection.parts();
            if (components.isEmpty()) {
                targets.add(new ReservationTarget(cartItem, cartItem.getVariant(), cartItem.getQty(), false, 0));
            } else {
                int position = 0;
                for (PackageService.SelectedPart component : components) {
                    // A product the package comes with is available by the way the selection is made; one the shopper
                    // picked instead may have been taken off sale since (the cart refused it too, this is the same
                    // rule where the order is made).
                    if (component.changed() && !component.available()) {
                        throw new UnprocessableException(component.variant().getProduct().getName() + " in "
                                + cartItem.getProductNameSnapshot()
                                + " is no longer available. Please remove the package from your cart and choose again.");
                    }
                    targets.add(new ReservationTarget(cartItem, component.variant(),
                            component.qtyPerPackage().multiply(cartItem.getQty()), true, position++));
                }
            }
        }
        // The same demo rule, one level down: a package line expands into its components, and the line's own product
        // being real says nothing about them (a REAL package can be assembled from demo variants while demo mode is on,
        // since the package picker then only offers demo ones). Checked before anything is reserved or charged.
        for (ReservationTarget target : targets) {
            if (target.variant().getProduct() != null && target.variant().getProduct().isMockData()) {
                throw new UnprocessableException(
                        com.nbh.backend.cart.service.CartService.demoProductMessage(
                                target.cartItem().getVariant().getProduct().getName())
                                + " Please remove it from your cart and try again.");
            }
        }
        targets.sort(java.util.Comparator.comparing(t -> t.variant().getId()));

        // Reserving here — not at add-to-cart — is the exact "hold starts at checkout" decision.
        // If any target throws (insufficient availability), the whole @Transactional method rolls
        // back, including the order row saved above — nothing is left half-created.
        record ReservedComponent(ProductVariant variant, BigDecimal qty, StockReservation reservation, int position) {}
        java.util.Map<CartItem, StockReservation> reservationByCartItem = new java.util.LinkedHashMap<>();
        java.util.Map<CartItem, List<ReservedComponent>> componentsByCartItem = new java.util.LinkedHashMap<>();
        // An option that is not tracked yet (no stock number, a "sell without stock" switch on) is sold without a hold: its
        // line carries no reservation, so dispatch, cancelling and returns move no stock for it (StockTrackingService).
        java.util.Set<java.util.UUID> untracked = stockTracking == null ? java.util.Set.of()
                : stockTracking.untracked(targets.stream().map(t -> t.variant().getId()).toList());
        for (ReservationTarget target : targets) {
            StockReservation reservation;
            if (untracked.contains(target.variant().getId())) {
                reservation = null;
            } else if (sellsRegardlessOfStock() && !shopperStock.canHold(target.variant().getId(), target.qty())) {
                // Selling regardless of stock (V101): the records say there is not enough, but they are not trusted yet,
                // so this line goes without a hold, as for an option not tracked. Asked before reserving: a refused hold
                // would mark the whole order's transaction to be undone.
                reservation = null;
            } else try {
                reservation = reservationService.reserve(
                        target.variant(), target.qty(),
                        StockReservation.ReservationSource.ONLINE_CART,
                        "CUSTOMER_ORDER", order.getId(), expiresAt);
            } catch (InsufficientAvailabilityException e) {
                // Named after the cart item's own product even when the shortfall is actually on
                // one of its package components — the customer never added that component SKU directly
                // and shouldn't need to know it exists.
                CartItem cartItem = target.cartItem();
                if (!placement.byStaff()) {
                    recordShortfall(cartItem, sessionId, request);
                }
                throw new InsufficientAvailabilityException(cartItem.getVariant().displayLabel()
                        + (placement.byStaff() ? " is not available in that quantity right now"
                                : " just went out of stock — please update your cart"));
            }
            if (target.isPackageComponent()) {
                componentsByCartItem.computeIfAbsent(target.cartItem(), k -> new java.util.ArrayList<>())
                        .add(new ReservedComponent(target.variant(), target.qty(), reservation, target.position()));
            } else {
                reservationByCartItem.put(target.cartItem(), reservation);
            }
        }

        // Every line, package components included, freezes what it was bought as (name, colour, size, SKU, photo): editing
        // the variant later must never rewrite an order that already exists. One lookup for all the covers.
        java.util.Map<java.util.UUID, ProductVariant> soldVariants = new java.util.LinkedHashMap<>();
        for (CartItem cartItem : cartItems) {
            soldVariants.putIfAbsent(cartItem.getVariant().getId(), cartItem.getVariant());
        }
        for (ReservationTarget target : targets) {
            soldVariants.putIfAbsent(target.variant().getId(), target.variant());
        }
        java.util.Map<java.util.UUID, String> coverByVariant = variantCovers.urlsFor(soldVariants.values());

        // The order the lines were entered in: each package line, then its own products in the package's order.
        int lineNo = 0;
        for (CartItem cartItem : cartItems) {
            CustomerOrderItem orderItem = new CustomerOrderItem();
            orderItem.setOrder(order);
            orderItem.setLineNo(++lineNo);
            orderItem.setVariant(cartItem.getVariant());
            orderItem.rememberVariant(cartItem.getVariant(), coverByVariant.get(cartItem.getVariant().getId()));
            orderItem.setQty(cartItem.getQty());
            orderItem.setUnitPrice(cartItem.getUnitPriceSnapshot());
            orderItem.setWholesaleApplied(cartItem.isWholesaleApplied());
            // Which campaign deal the line was sold under, so the deal's cap counts it (owner, 2026-11-04).
            orderItem.setFreeUnderDeal(cartItem.isFree());
            orderItem.setCampaignDealId(cartItem.isFree() ? cartItem.getFreeDealId() : dealIdOf(cartItem));
            orderItem.setVatRate(cartItem.getVariant().getVatRate());
            orderItem.setLineTotal(cartItem.getLineTotal());
            // Null for a package — its own SKU holds no stock, so it gets no reservation; see the
            // child component lines added below, each with its own genuine reservation.
            orderItem.setReservation(reservationByCartItem.get(cartItem));
            order.getItems().add(orderItem);

            // The package's products in the package's own order (they were reserved in variant-id order, which means nothing to
            // the person packing the box).
            List<ReservedComponent> inPackageOrder = new java.util.ArrayList<>(
                    componentsByCartItem.getOrDefault(cartItem, List.of()));
            inPackageOrder.sort(java.util.Comparator.comparingInt(ReservedComponent::position));
            for (ReservedComponent component : inPackageOrder) {
                BigDecimal unitPrice = component.variant().getSellingPrice() == null
                        ? BigDecimal.ZERO : component.variant().getSellingPrice();
                CustomerOrderItem componentItem = new CustomerOrderItem();
                componentItem.setOrder(order);
                componentItem.setLineNo(++lineNo);
                componentItem.setVariant(component.variant());
                componentItem.rememberVariant(component.variant(), coverByVariant.get(component.variant().getId()));
                componentItem.setQty(component.qty());
                componentItem.setUnitPrice(unitPrice);
                componentItem.setVatRate(component.variant().getVatRate());
                componentItem.setLineTotal(unitPrice.multiply(component.qty()));
                componentItem.setReservation(component.reservation());
                // Informational only — never summed into subtotal/vatTotal below, which is still
                // computed strictly once per cart item, exactly as before packages existed.
                componentItem.setParentItem(orderItem);
                order.getItems().add(componentItem);
            }

            subtotal = subtotal.add(cartItem.getLineTotal());
            if (!cartItem.getVariant().isVatExempt()) {
                BigDecimal vatRate = cartItem.getVariant().getVatRate();
                BigDecimal vatComponent = cartItem.getLineTotal()
                        .multiply(vatRate)
                        .divide(BigDecimal.ONE.add(vatRate), 2, RoundingMode.HALF_UP);
                vatTotal = vatTotal.add(vatComponent);
            }
        }

        order.setSubtotal(subtotal);
        order.setVatTotal(vatTotal);
        order.setGrandTotal(subtotal.add(order.getDeliveryFee()));
        order = orderRepository.save(order);

        cart.setStatus(Cart.CartStatus.CONVERTED);
        cartRepository.save(cart);

        PaymentRecord paymentRecord = new PaymentRecord();
        paymentRecord.setOrder(order);
        paymentRecord.setProvider("BANK");
        paymentRecord.setMethod(PaymentRecord.PaymentMethod.BANK);
        paymentRecord.setPaymentReference(uniqueReference());
        paymentRecord.setExpiresAt(expiresAt);
        paymentRecord.setAmount(order.getGrandTotal());
        paymentRecord.setStatus(PaymentRecord.PaymentStatus.INITIATED);
        paymentRecord = paymentRecordRepository.save(paymentRecord);

        // §2: published from inside this @Transactional method, delivered
        // AFTER_COMMIT — see EventLoggingListener's javadoc for why (this exact
        // method can still roll back after this point if a later line's
        // reservation fails, which would already have happened above had it
        // failed; this call only runs once every line succeeded).
        eventPublisher.publishEvent(new OrderPlacedEvent(
                order.getId(), order.getOrderNo(), order.getGuestEmail(), order.getGuestPhone(),
                order.getGrandTotal(), cartItems.size()));

        return new CheckoutResult(order, paymentRecord);
    }

    /**
     * The checks the checkout form already makes, repeated where they cannot be skipped: staff call the customer on
     * this number to settle payment and delivery, so a made-up one (found in the 2026-10-06 go-live QA: "12345" and
     * "abc" were accepted) leaves an order nobody can act on, and a delivery with no place to deliver to cannot be sent.
     */
    /**
     * The campaign deal a paid line was sold under: a % or amount deal that priced it (not wholesale, which may have won),
     * or a buy-X-get-Y deal (its paid pieces count toward the cap too); null otherwise.
     */
    private java.util.UUID dealIdOf(CartItem cartItem) {
        if (liveDeals == null || cartItem.getVariant().isPackage() || cartItem.isWholesaleApplied()) return null;
        com.nbh.backend.catalog.service.LiveDeals.LiveDeal deal = liveDeals.dealFor(cartItem.getVariant());
        if (deal == null) return null;
        if (deal.isBuyGet()) return deal.dealId();
        return cartItem.getUnitPriceSnapshot().compareTo(deal.priceOf(cartItem.getVariant().getSellingPrice())) == 0
                ? deal.dealId() : null;
    }

    private static void requireUsableContactDetails(CheckoutRequest request) {
        if (!PhoneNumbers.isValidKenyanMobile(request.guestPhone())) {
            throw new IllegalArgumentException("Enter a valid Kenyan mobile number, e.g. 0720 000 111");
        }
        String dropoffContact = request.deliveryContactPhone();
        if (dropoffContact != null && !dropoffContact.isBlank() && !PhoneNumbers.isValidKenyanMobile(dropoffContact)) {
            throw new IllegalArgumentException("Enter a valid Kenyan mobile number for the person receiving the delivery, e.g. 0720 000 111");
        }
        if (request.fulfilmentType() == FulfilmentType.DELIVERY_COURIER
                && (request.deliveryAddress() == null || request.deliveryAddress().isBlank())) {
            throw new IllegalArgumentException("Tell us where to deliver the order");
        }
    }

    /**
     * Tells staff a shopper could not order this line for stock (owner, 2026-10-28), with the details they typed so
     * someone can call them. A failure to write it down never changes what the shopper is told.
     */
    private void recordShortfall(CartItem cartItem, String sessionId, CheckoutRequest request) {
        if (shortfalls == null) return;
        try {
            var variant = cartItem.getVariant();
            // A package has no shelf of its own (its parts do), so what is left of it is not one number: 0 is shown.
            BigDecimal left = variant.isPackage() || availability == null
                    ? BigDecimal.ZERO : availability.available(variant.getId());
            shortfalls.record(variant.getId(), variant.displayLabel(), cartItem.getQty(), left,
                    com.nbh.backend.stock.entity.StockShortfallEvent.Source.CHECKOUT, sessionId,
                    request.customerName(), request.guestPhone(), request.guestEmail());
        } catch (RuntimeException e) {
            log.warn("Could not record a stock shortfall at checkout: {}", e.getMessage());
        }
    }

    /** 30^6 combinations makes a clash rare; the unique index is the real guard, this just avoids a 500 on the odd hit. */
    private String uniqueReference() {
        for (int attempt = 0; attempt < REFERENCE_ATTEMPTS; attempt++) {
            String candidate = referenceGenerator.generate();
            if (paymentRecordRepository.findByPaymentReference(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique payment reference");
    }

    public record CheckoutResult(CustomerOrder order, PaymentRecord paymentRecord) {}
}
