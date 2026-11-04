package com.nbh.backend.cart.service;

import com.nbh.backend.cart.dto.CartDto;
import com.nbh.backend.cart.dto.CartItemDto;
import com.nbh.backend.cart.dto.PackagePartDto;
import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.EffectivePriceResolver;
import com.nbh.backend.catalog.service.PromoCampaignService;
import com.nbh.backend.catalog.service.VariantCovers;
import com.nbh.backend.common.exception.PriceChangedException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.packages.service.PackageAvailabilityService;
import com.nbh.backend.packages.service.PackageChoices;
import com.nbh.backend.packages.service.PackageService;
import com.nbh.backend.stock.service.QuantityRules;
import com.nbh.backend.stock.service.StockAvailabilityService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guest cart — no reservation happens here. Founder's confirmed decision
 * (session 5): "don't hold at add-to-cart; hold for 15 min at checkout" — an
 * abandoned cart must never starve real buyers of a popular item. This
 * service only checks CURRENT availability as a courtesy at add-time (so a
 * customer isn't told "in stock" for something with zero left); the actual
 * guarantee against overselling happens once at checkout, when
 * {@link com.nbh.backend.order.service.CheckoutService} calls
 * {@link com.nbh.backend.stock.service.StockReservationService#reserve}.
 *
 * <p>A package line may carry the shopper's picks (another colour or size of a product inside the package, owner 2026-10-19):
 * the line is then identified by the package AND its picks, priced at the package's price plus the difference the picks make
 * ({@link PackageService#priceDelta}) and counted against the shelves of the variants picked.
 */
@Service
public class CartService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CartService.class);

    /** Optional so the unit tests can build this service by hand (then refusals are not written down). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.stock.service.StockShortfallService shortfalls;

    /** Campaign deals (owner, 2026-11-04): which buy-X-get-Y deals give free pieces. Optional for hand-built tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nbh.backend.catalog.service.LiveDeals liveDeals;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository variantRepository;
    private final StockAvailabilityService availabilityService;
    private final PackageAvailabilityService packageAvailability;
    private final EffectivePriceResolver priceResolver;
    private final PromoCampaignService promoCampaignService;
    private final PackageService packageService;
    private final VariantCovers variantCovers;

    public CartService(CartRepository cartRepository,
                        CartItemRepository cartItemRepository,
                        ProductVariantRepository variantRepository,
                        StockAvailabilityService availabilityService,
                        PackageAvailabilityService packageAvailability,
                        EffectivePriceResolver priceResolver,
                        PromoCampaignService promoCampaignService,
                        PackageService packageService,
                        VariantCovers variantCovers) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.variantRepository = variantRepository;
        this.availabilityService = availabilityService;
        this.packageAvailability = packageAvailability;
        this.priceResolver = priceResolver;
        this.promoCampaignService = promoCampaignService;
        this.packageService = packageService;
        this.variantCovers = variantCovers;
    }

    @Transactional
    public CartDto getOrCreateCart(String sessionId) {
        Cart cart = findOrCreateCart(sessionId);
        return toDto(cart);
    }

    /**
     * What this session's basket holds, in KES, at TODAY's prices — what staff see while entering an
     * order. Zero for an unknown, empty or already-checked-out session, and unlike {@link #getOrCreateCart} it never
     * creates a cart (or writes anything: the basket itself is re-priced when it is read).
     */
    @Transactional(readOnly = true)
    public BigDecimal basketValue(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return BigDecimal.ZERO;
        }
        PromoCampaign campaign = promoCampaignService.getActiveOrNull();
        return cartRepository.findBySessionId(sessionId)
                .filter(cart -> cart.getStatus() == Cart.CartStatus.ACTIVE)
                .map(cart -> cartItemRepository.findByCartId(cart.getId()).stream()
                        .map(item -> currentPrice(item, campaign).effectivePrice().multiply(item.getQty()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Whether this session's basket has already been turned into an order. Read after {@link #refreshPricesForCheckout}
     * (which takes the lock), it is how staff entering an order tell a second submit of the same basket — already placed by
     * the first — from a basket that was never filled.
     */
    @Transactional(readOnly = true)
    public boolean isCheckedOut(String sessionId) {
        return sessionId != null && !sessionId.isBlank()
                && cartRepository.findBySessionId(sessionId).filter(cart -> cart.getStatus() == Cart.CartStatus.CONVERTED).isPresent();
    }

    /**
     * The first thing checkout does with a basket: bring every line up to today's price and make sure
     * everything in it can still be bought, then — if the caller says what total the shopper was shown —
     * refuse (409 {@code PRICE_CHANGED}) when the real total is different, so nobody is ever charged an
     * amount they did not see. Runs in the caller's transaction, so a refusal leaves nothing behind.
     *
     * @param expectedSubtotal what the shopper was shown, or null to skip the comparison (older clients)
     */
    @Transactional
    public void refreshPricesForCheckout(String sessionId, BigDecimal expectedSubtotal) {
        // The basket is read UNDER the checkout lock, here, as the very first thing the transaction does. Checkout takes
        // the same lock a few lines later, but a lock does not refresh a basket this transaction has already read: a
        // second simultaneous submit (a double-click, a retry) used to read it open before the first had finished,
        // wait for the lock, then carry on with that stale copy and make a second order and a second stock hold.
        // Reading it under the lock makes the second submit wait first and then see it checked out.
        Cart cart = cartRepository.findWithLockBySessionId(sessionId)
                .filter(c -> c.getStatus() == Cart.CartStatus.ACTIVE)
                .orElse(null);
        if (cart == null) {
            return; // nothing to price; checkout itself reports a missing or already-converted basket
        }
        reconcileFreePieces(cart);
        Refreshed refreshed = refreshPrices(cart);
        for (CartItem item : refreshed.items()) {
            if (item.getVariant().getProduct().isMockData()) {
                throw new UnprocessableException(demoProductMessage(item.getProductNameSnapshot())
                        + " Please remove it from your cart and try again.");
            }
            if (!isPurchasable(item.getVariant())) {
                throw new UnprocessableException(item.getProductNameSnapshot()
                        + " is no longer available. Please remove it from your cart and try again.");
            }
            // What the shopper picked inside a package has to be buyable too, or the order could not be packed as shown, and
            // has to still apply: when staff have since stopped a product being changed, or the colour is gone, the
            // order is not quietly packed as another colour.
            if (item.getVariant().isPackage() && !item.getChoices().isEmpty()) {
                PackageService.Selection selection = packageService.selection(item.getVariant().getId(), item.getChoices());
                if (selection.outOfDate()) {
                    throw new UnprocessableException(outOfDatePackageMessage(item.getProductNameSnapshot()));
                }
                for (PackageService.SelectedPart part : selection.parts()) {
                    if (!part.available()) {
                        String option = PackageService.optionLabel(part.variant());
                        throw new UnprocessableException(part.variant().getProduct().getName()
                                + (option == null ? "" : " (" + option + ")") + " in " + item.getProductNameSnapshot()
                                + " is no longer available. Please remove the package from your cart and choose again.");
                    }
                }
            }
        }
        BigDecimal subtotal = refreshed.items().stream()
                .map(CartItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (expectedSubtotal != null && expectedSubtotal.compareTo(subtotal) != 0) {
            throw new PriceChangedException("Some prices changed while you were checking out. We've updated your"
                    + " cart: please look over the new total and place your order again.");
        }
    }

    @Transactional
    public CartDto addItem(String sessionId, UUID variantId, BigDecimal qty) {
        return addItem(sessionId, variantId, qty, null);
    }

    /**
     * {@code packageChoices} is only for a package: what the shopper picked instead of what it comes with (the default variant's id
     * to another colour or size of the same product). The same package with different picks is a different basket line.
     */
    @Transactional
    public CartDto addItem(String sessionId, UUID variantId, BigDecimal qty, Map<UUID, UUID> packageChoices) {
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        Cart cart = findOrCreateCart(sessionId);
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new EntityNotFoundException("No product variant: " + variantId));
        requireSellable(variant);
        QuantityRules.requireSensible(variant.getProduct().getName(), variant, qty);

        // What was picked inside a package is checked here and kept only where it differs from the package as it comes.
        Map<UUID, UUID> picks = packageChoices == null || packageChoices.isEmpty()
                ? Map.of() : packageService.normalizeChoices(variantId, packageChoices);
        String choiceKey = PackageChoices.keyOf(picks);
        CartItem item = cartItemRepository.findByCartIdAndVariantIdAndChoiceKey(cart.getId(), variantId, choiceKey)
                .orElseGet(() -> {
                    CartItem newItem = new CartItem();
                    newItem.setCart(cart);
                    newItem.setVariant(variant);
                    newItem.setQty(BigDecimal.ZERO);
                    newItem.setChoices(new HashMap<>(picks));
                    newItem.setChoiceKey(choiceKey);
                    return newItem;
                });
        // The whole line, not just this click: with 3 in stock and 2 already in the basket,
        // adding 2 more used to pass because each add was compared to stock on its own.
        checkCourtesyAvailability(variant, item.getQty().add(qty), item.getChoices(), sessionId);
        item.setQty(item.getQty().add(qty));
        applyPriceForQty(item);
        item.setProductNameSnapshot(variant.getProduct().getName());
        item.setVariantNameSnapshot(variant.getVariantName());
        cartItemRepository.save(item);

        return toDto(cart);
    }

    @Transactional
    public CartDto updateItemQty(String sessionId, UUID itemId, BigDecimal qty) {
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive — use the remove endpoint to delete a line");
        }
        Cart cart = findOrCreateCart(sessionId);
        CartItem item = cartItemRepository.findById(itemId)
                .filter(i -> i.getCart().getId().equals(cart.getId()))
                .orElseThrow(() -> new EntityNotFoundException("No cart item: " + itemId));
        requireNotFree(item);

        requireSellable(item.getVariant());
        QuantityRules.requireSensible(item.getVariant().getProduct().getName(), item.getVariant(), qty);
        checkCourtesyAvailability(item.getVariant(), qty, item.getChoices(), sessionId);
        item.setQty(qty);
        // Repriced here rather than left to toDto's refresh: crossing a wholesale threshold is the
        // shopper's own doing, not a price that moved under them, so it must not come back flagged
        // as previousUnitPrice ("was KSh X, now KSh Y").
        applyPriceForQty(item);
        cartItemRepository.save(item);

        return toDto(cart);
    }

    /**
     * The real, server-computed price for the line's current quantity — a variant's own deal, else an
     * active site-wide campaign, else its wholesale price if the quantity qualifies (cheapest of those
     * wins), else the plain selling price (see EffectivePriceResolver); for a package with picks, plus what the picks
     * add or take off. This snapshot is what checkout later trusts without re-resolving, so it must be right here,
     * not just at display time.
     */
    private void applyPriceForQty(CartItem item) {
        EffectivePriceResolver.EffectivePrice price = withPicks(item,
                priceResolver.resolve(item.getVariant(), promoCampaignService.getActiveOrNull(), item.getQty()));
        item.setUnitPriceSnapshot(price.effectivePrice());
        item.setWholesaleApplied(price.source() == EffectivePriceResolver.Source.WHOLESALE);
    }

    /**
     * A package's price as it comes, moved by the shopper's picks: for each product they changed, the quantity times the
     * difference in selling price, at the package's share of its products' price ({@link PackageService#priceDelta}). Never below zero.
     */
    private EffectivePriceResolver.EffectivePrice withPicks(CartItem item, EffectivePriceResolver.EffectivePrice price) {
        if (!item.getVariant().isPackage() || item.getChoices().isEmpty()) {
            return price;
        }
        BigDecimal delta = packageService.priceDelta(item.getVariant().getId(), item.getChoices());
        if (delta.signum() == 0) {
            return price;
        }
        return new EffectivePriceResolver.EffectivePrice(price.originalPrice().add(delta).max(BigDecimal.ZERO),
                price.effectivePrice().add(delta).max(BigDecimal.ZERO), price.dealEndsAt(), price.source());
    }

    @Transactional
    public CartDto removeItem(String sessionId, UUID itemId) {
        Cart cart = findOrCreateCart(sessionId);
        CartItem item = cartItemRepository.findById(itemId)
                .filter(i -> i.getCart().getId().equals(cart.getId()))
                .orElseThrow(() -> new EntityNotFoundException("No cart item: " + itemId));
        requireNotFree(item);
        cartItemRepository.delete(item);
        return toDto(cart);
    }

    /** Free pieces follow what is paid for; the shopper changes their shades instead ({@link #setFreeShades}). */
    private static void requireNotFree(CartItem item) {
        if (item.isFree()) {
            throw new UnprocessableException("Free pieces follow what you buy. Change how many you pay for, or pick other"
                    + " shades for your free pieces.");
        }
    }

    /**
     * The shopper picks the shades of their free pieces under a buy-X-get-Y deal (owner, 2026-11-04): {@code shades} is
     * how many of each option of the deal's product, adding up to the free pieces they have now. Each shade must be one
     * the deal covers and have the stock for it.
     */
    @Transactional
    public CartDto setFreeShades(String sessionId, UUID dealId, Map<UUID, BigDecimal> shades) {
        Cart cart = findOrCreateCart(sessionId);
        reconcileFreePieces(cart);
        List<CartItem> free = cartItemRepository.findByCartId(cart.getId()).stream()
                .filter(i -> dealId.equals(i.getFreeDealId())).sorted(CartItem.IN_ORDER_ADDED).toList();
        if (free.isEmpty()) {
            throw new UnprocessableException("You have no free pieces under that deal any more.");
        }
        BigDecimal have = free.stream().map(CartItem::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<UUID, BigDecimal> wanted = new java.util.LinkedHashMap<>();
        shades.forEach((v, q) -> { if (v != null && q != null && q.signum() > 0) wanted.merge(v, q, BigDecimal::add); });
        BigDecimal asked = wanted.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (asked.compareTo(have) != 0) {
            throw new UnprocessableException("Choose shades for exactly " + have.stripTrailingZeros().toPlainString()
                    + " free pieces.");
        }
        UUID productId = free.get(0).getVariant().getProduct().getId();
        Map<UUID, BigDecimal> paid = new HashMap<>();
        for (CartItem i : cartItemRepository.findByCartId(cart.getId())) {
            if (!i.isFree() && !i.getVariant().isPackage()) paid.merge(i.getVariant().getId(), i.getQty(), BigDecimal::add);
        }
        Map<UUID, BigDecimal> shelves = availabilityService.available(wanted.keySet());
        Map<UUID, ProductVariant> chosen = new HashMap<>();
        for (Map.Entry<UUID, BigDecimal> e : wanted.entrySet()) {
            ProductVariant v = variantRepository.findById(e.getKey())
                    .orElseThrow(() -> new EntityNotFoundException("No product variant: " + e.getKey()));
            if (!v.getProduct().getId().equals(productId) || !isPurchasable(v) || !dealId.equals(dealOf(v))) {
                throw new UnprocessableException(v.displayLabel() + " isn't one of the shades this deal gives free.");
            }
            BigDecimal room = shelves.getOrDefault(v.getId(), BigDecimal.ZERO).subtract(paid.getOrDefault(v.getId(), BigDecimal.ZERO));
            if (room.compareTo(e.getValue()) < 0) {
                throw new UnprocessableException(room.signum() <= 0 ? v.displayLabel() + " has none left to give free."
                        : "Only " + room.stripTrailingZeros().toPlainString() + " of " + v.displayLabel() + " left to give free.");
            }
            chosen.put(v.getId(), v);
        }
        cartItemRepository.deleteAll(free);
        cartItemRepository.flush();
        for (Map.Entry<UUID, BigDecimal> e : wanted.entrySet()) {
            saveFreeLine(cart, chosen.get(e.getKey()), dealId, e.getValue());
        }
        return toDto(cart);
    }

    /**
     * Brings the basket's free pieces into line with what is paid for, under every live buy-X-get-Y deal (owner,
     * 2026-11-04), and takes away free pieces whose deal has ended or no longer applies. See {@link FreePieces} for how
     * many and which shades. Returns what was added, so the shop can say so.
     */
    private List<com.nbh.backend.cart.dto.CartDto.FreeAdded> reconcileFreePieces(Cart cart) {
        List<com.nbh.backend.cart.dto.CartDto.FreeAdded> added = new java.util.ArrayList<>();
        List<CartItem> lines = cartItemRepository.findByCartId(cart.getId()).stream().sorted(CartItem.IN_ORDER_ADDED).toList();
        if (lines.isEmpty() || (liveDeals == null && lines.stream().noneMatch(CartItem::isFree))) {
            return added;
        }
        // The live buy-X-get-Y deal of each paid line, and the paid pieces under each deal by option.
        Map<UUID, com.nbh.backend.catalog.service.LiveDeals.LiveDeal> dealById = new HashMap<>();
        Map<UUID, Map<UUID, BigDecimal>> paidByDeal = new HashMap<>();
        Map<UUID, ProductVariant> sampleOf = new HashMap<>();
        for (CartItem line : lines) {
            if (line.isFree() || line.getVariant().isPackage() || liveDeals == null || !isPurchasable(line.getVariant())) continue;
            com.nbh.backend.catalog.service.LiveDeals.LiveDeal deal = liveDeals.dealFor(line.getVariant());
            if (deal == null || !deal.isBuyGet()) continue;
            dealById.put(deal.dealId(), deal);
            sampleOf.putIfAbsent(deal.dealId(), line.getVariant());
            paidByDeal.computeIfAbsent(deal.dealId(), k -> new java.util.LinkedHashMap<>())
                    .merge(line.getVariant().getId(), line.getQty(), BigDecimal::add);
        }
        // Free lines whose deal no longer gives this basket anything go.
        Map<UUID, List<CartItem>> freeByDeal = new HashMap<>();
        boolean removed = false;
        for (CartItem line : lines) {
            if (!line.isFree()) continue;
            if (!dealById.containsKey(line.getFreeDealId())) {
                cartItemRepository.delete(line);
                removed = true;
                continue;
            }
            freeByDeal.computeIfAbsent(line.getFreeDealId(), k -> new java.util.ArrayList<>()).add(line);
        }
        if (removed) cartItemRepository.flush();
        for (com.nbh.backend.catalog.service.LiveDeals.LiveDeal deal : dealById.values()) {
            Map<UUID, BigDecimal> paid = paidByDeal.getOrDefault(deal.dealId(), Map.of());
            List<CartItem> freeLines = freeByDeal.getOrDefault(deal.dealId(), List.of());
            Map<UUID, BigDecimal> freeNow = new java.util.LinkedHashMap<>();
            freeLines.forEach(l -> freeNow.merge(l.getVariant().getId(), l.getQty(), BigDecimal::add));
            // The options this deal covers that can be sold, in the order they are offered.
            ProductVariant sample = sampleOf.get(deal.dealId());
            List<ProductVariant> options = variantRepository
                    .findByProductIdAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNullOrderBySortOrderAsc(sample.getProduct().getId())
                    .stream().filter(CartService::isPurchasable).filter(v -> deal.dealId().equals(dealOf(v))).toList();
            Map<UUID, BigDecimal> offered = new java.util.LinkedHashMap<>();
            options.forEach(v -> offered.put(v.getId(), paid.getOrDefault(v.getId(), BigDecimal.ZERO)));
            Map<UUID, BigDecimal> shelves = availabilityService.available(offered.keySet());
            Map<UUID, BigDecimal> plan = FreePieces.plan(deal.buyQty(), deal.freeQty(), offered, freeNow, shelves);
            if (samePieces(plan, freeNow)) continue;
            BigDecimal before = FreePieces.sum(freeNow);
            BigDecimal after = FreePieces.sum(plan);
            Map<UUID, ProductVariant> optionById = new HashMap<>();
            options.forEach(v -> optionById.put(v.getId(), v));
            for (CartItem l : freeLines) {
                if (!plan.containsKey(l.getVariant().getId())) cartItemRepository.delete(l);
            }
            cartItemRepository.flush();
            for (Map.Entry<UUID, BigDecimal> p : plan.entrySet()) {
                CartItem existing = freeLines.stream().filter(l -> l.getVariant().getId().equals(p.getKey())).findFirst().orElse(null);
                if (existing != null) {
                    if (existing.getQty().compareTo(p.getValue()) != 0) {
                        existing.setQty(p.getValue());
                        cartItemRepository.save(existing);
                    }
                } else {
                    saveFreeLine(cart, optionById.get(p.getKey()), deal.dealId(), p.getValue());
                }
            }
            if (after.compareTo(before) > 0) {
                added.add(new com.nbh.backend.cart.dto.CartDto.FreeAdded(deal.dealId(), sample.getProduct().getName(), after.subtract(before), deal.campaignTitle()));
            }
        }
        return added;
    }

    /** The same pieces of the same options (quantities compared by value, not by how they are written). */
    private static boolean samePieces(Map<UUID, BigDecimal> a, Map<UUID, BigDecimal> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        return a.entrySet().stream().allMatch(e -> e.getValue().compareTo(b.get(e.getKey())) == 0);
    }

    /** The id of the live campaign deal an option is on, or null. */
    private UUID dealOf(ProductVariant v) {
        com.nbh.backend.catalog.service.LiveDeals.LiveDeal d = liveDeals == null ? null : liveDeals.dealFor(v);
        return d == null ? null : d.dealId();
    }

    private void saveFreeLine(Cart cart, ProductVariant variant, UUID dealId, BigDecimal qty) {
        CartItem line = new CartItem();
        line.setCart(cart);
        line.setVariant(variant);
        line.setQty(qty);
        line.setUnitPriceSnapshot(BigDecimal.ZERO);
        line.setFreeDealId(dealId);
        line.setChoiceKey(CartItem.freeKey(dealId));
        line.setProductNameSnapshot(variant.getProduct().getName());
        line.setVariantNameSnapshot(variant.getVariantName());
        line.setAddedAt(java.time.Instant.now());
        cartItemRepository.save(line);
    }

    private Cart findOrCreateCart(String sessionId) {
        return cartRepository.findBySessionId(sessionId).map(this::reactivateIfClosed).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setSessionId(sessionId);
            return cartRepository.save(cart);
        });
    }

    /**
     * session_id is unique, so a browser that checked out and then keeps
     * shopping under the same session can't get a second cart row. A closed
     * (CONVERTED/ABANDONED) cart is therefore emptied and reopened — its lines
     * already live on as order items, so nothing is lost, and the customer
     * never sees last order's items reappear in a new basket.
     */
    private Cart reactivateIfClosed(Cart cart) {
        if (cart.getStatus() == Cart.CartStatus.ACTIVE) {
            return cart;
        }
        cartItemRepository.deleteAll(cartItemRepository.findByCartId(cart.getId()));
        cart.setStatus(Cart.CartStatus.ACTIVE);
        return cartRepository.save(cart);
    }

    /**
     * A deleted/hidden variant, or one whose product is unpublished/deleted, must never be
     * addable to a cart — found live while building Phase 3's editor (2026-09-24): nothing
     * previously checked this, so a variant an admin had just soft-deleted or hidden stayed
     * addable to a cart that already had a reference to it (e.g. reopened from local storage).
     */
    private void requireSellable(ProductVariant variant) {
        if (variant.getProduct().isMockData()) {
            throw new UnprocessableException(demoProductMessage(variant.getProduct().getName()));
        }
        if (!isPurchasable(variant)) {
            throw new IllegalArgumentException("This item is no longer available");
        }
    }

    /**
     * Sellable, published, and REAL. Demo-catalogue products (see MockDataSeederService) are never for sale,
     * whether demo mode is on or off: they exist to show the shop populated, and an order for one would take a
     * real payment for something that does not exist. Real products stay purchasable whatever the demo state is.
     */
    private static boolean isPurchasable(ProductVariant variant) {
        return variant.isSellable() && variant.getProduct().isPublished() && !variant.getProduct().isDeleted()
                && !variant.getProduct().isMockData();
    }

    /** What a shopper is told when a demo-catalogue product is in their basket or they try to add one. */
    public static String demoProductMessage(String productName) {
        return productName + " is a demo product and can't be ordered.";
    }

    /** What a shopper is told when a colour or size they picked inside a package can no longer be given to them. */
    public static String outOfDatePackageMessage(String packageName) {
        return "A colour or size you picked in " + packageName + " is not on offer any more. Please remove the package from your"
                + " cart and choose again.";
    }

    /**
     * What a shopper is told when the basket line asks for more than is in stock. The exact stock
     * figure is only shown when it is low (Odoo's website_sale_stock does the same with its
     * available_threshold, default 5) — anything higher tells a competitor how deep the shelf is,
     * and the old message also printed the internal SKU.
     */
    private static final BigDecimal LOW_STOCK_THRESHOLD = new BigDecimal("5");

    private void checkCourtesyAvailability(ProductVariant variant, BigDecimal requestedQty, Map<UUID, UUID> picks,
                                           String sessionId) {
        // A package holds no StockBalance of its own — what it can sell is however many whole packages its components
        // can still make up (with the shopper's picks, from the shelves of the colours and sizes they chose).
        // Checkout still reserves each component and is the final word.
        BigDecimal available;
        if (!variant.isPackage()) {
            available = availabilityService.available(variant.getId());
        } else if (picks.isEmpty()) {
            available = packageAvailability.wholePackagesAvailable(java.util.List.of(variant.getId()))
                    .getOrDefault(variant.getId(), BigDecimal.ZERO);
        } else {
            available = packageAvailability.wholePackagesFrom(partsOf(packageService.selectionFor(variant.getId(), picks)));
        }
        if (available.compareTo(requestedQty) >= 0) {
            return;
        }
        String name = variant.displayLabel();
        recordShortfall(variant, name, requestedQty, available, sessionId);
        if (available.signum() <= 0) {
            throw new IllegalArgumentException(name + " is out of stock");
        }
        if (available.compareTo(LOW_STOCK_THRESHOLD) <= 0) {
            throw new IllegalArgumentException(
                    "Only " + available.stripTrailingZeros().toPlainString() + " of " + name + " left");
        }
        throw new IllegalArgumentException("That quantity of " + name + " isn't available right now");
    }

    /** Staff are told a shopper could not have it (owner, 2026-10-28); a failure to write it down never changes the answer. */
    private void recordShortfall(ProductVariant variant, String name, BigDecimal asked, BigDecimal left, String sessionId) {
        if (shortfalls == null) return;
        try {
            shortfalls.record(variant.getId(), name, asked, left,
                    com.nbh.backend.stock.entity.StockShortfallEvent.Source.CART, sessionId,
                    null, null, null);
        } catch (RuntimeException e) {
            log.warn("Could not record a stock shortfall for {}: {}", variant.getId(), e.getMessage());
        }
    }

    /** The parts a shelf is counted against: for each product of the package, the variant that will be packed. */
    private static List<PackageAvailabilityService.Part> partsOf(List<PackageService.SelectedPart> selection) {
        return selection.stream().map(p -> new PackageAvailabilityService.Part(p.variant(), p.qtyPerPackage())).toList();
    }

    /**
     * Every read of the basket first looks each line's price up again (a variant's deal, else an active
     * site-wide campaign, else the plain price — see {@link EffectivePriceResolver}) and stores it, so
     * the price shown and the price checkout charges are today's, never one remembered from whenever the
     * item went in. A price that moved is reported once, on the read that noticed it ({@code
     * previousUnitPrice}), so the shopper is told rather than surprised.
     *
     * <p>Replaces the old "snapshot at add time and trust it" rule, under which a deal that had ended
     * (or a price an admin had corrected) was still charged for as long as the item sat in a basket.
     */
    private CartDto toDto(Cart cart) {
        List<com.nbh.backend.cart.dto.CartDto.FreeAdded> freeAdded = reconcileFreePieces(cart);
        Refreshed refreshed = refreshPrices(cart);
        // What is inside each package line, with the shopper's picks applied (empty for every other line), and the lines whose
        // picks no longer apply (they are shown as unavailable, like a product that has been taken off sale).
        Map<UUID, List<PackageService.SelectedPart>> selections = new HashMap<>();
        java.util.Set<UUID> outOfDate = new java.util.HashSet<>();
        for (CartItem item : refreshed.items()) {
            if (item.getVariant().isPackage()) {
                PackageService.Selection selection = packageService.selection(item.getVariant().getId(), item.getChoices());
                selections.put(item.getId(), selection.parts());
                if (selection.outOfDate()) {
                    outOfDate.add(item.getId());
                }
            }
        }
        Map<UUID, BigDecimal> stockLeft = stockLeftFor(refreshed.items(), selections);
        // One photo lookup for everything shown from a package: the products inside it and the package's own thumbnail.
        Map<UUID, ProductVariant> shown = new HashMap<>();
        selections.values().forEach(parts -> parts.forEach(p -> shown.putIfAbsent(p.variant().getId(), p.variant())));
        refreshed.items().stream().filter(i -> i.getVariant().isPackage())
                .forEach(i -> shown.putIfAbsent(i.getVariant().getId(), i.getVariant()));
        Map<UUID, String> covers = variantCovers.urlsFor(shown.values());
        List<CartItemDto> items = refreshed.items().stream()
                .map(item -> {
                    List<PackageService.SelectedPart> parts = selections.getOrDefault(item.getId(), List.of());
                    boolean available = isPurchasable(item.getVariant())
                            && !outOfDate.contains(item.getId())
                            && parts.stream().allMatch(PackageService.SelectedPart::available);
                    com.nbh.backend.catalog.service.LiveDeals.LiveDeal deal = liveDeals == null || item.getVariant().isPackage() ? null : liveDeals.dealFor(item.getVariant());
                    boolean wholesaleShown = priceResolver.wholesaleOn() && (deal == null || deal.wholesaleApplies());
                    return CartItemDto.from(item, available,
                            stockLeft.getOrDefault(item.getId(), BigDecimal.ZERO),
                            refreshed.changedFrom().get(item.getId()), packagePartDtos(parts, covers),
                            item.getVariant().isPackage() ? covers.get(item.getVariant().getId()) : null,
                            wholesaleShown, deal);
                })
                .toList();
        return CartDto.from(cart, items, freeAdded);
    }

    private static List<PackagePartDto> packagePartDtos(List<PackageService.SelectedPart> parts, Map<UUID, String> covers) {
        return parts.stream().map(p -> new PackagePartDto(
                p.variant().getId(), p.defaultVariant().getId(), p.variant().getProduct().getName(),
                PackageService.optionLabel(p.variant()), p.variant().getOptionColor(), p.variant().getOptionSize(),
                p.variant().getSwatchHex(), p.qtyPerPackage(), covers.get(p.variant().getId()),
                p.changed(), p.available())).toList();
    }

    /**
     * What each line can still be sold, keyed by the BASKET LINE (the same package with different picks is several lines):
     * its own stock, or for a package the whole packages its components can make, from the shelves of the variants picked.
     * Lines draw on one shelf, so they are served in the order they were added: a line's figure is what is left AFTER
     * the lines before it took their share. A package and a loose unit of one of its parts, or two packages sharing a part,
     * therefore cannot both look fine while together asking for more than there is (checkout adds up what every line
     * needs from the same part before it reserves, and would refuse an order the cart had just called fine). The line
     * that does not fit is the one flagged.
     */
    private Map<UUID, BigDecimal> stockLeftFor(List<CartItem> items, Map<UUID, List<PackageService.SelectedPart>> selections) {
        List<CartItem> inOrder = items.stream()
                .sorted(java.util.Comparator.comparing(CartItem::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        // A package taken as it comes counts its own parts; one with picks counts the variants picked.
        List<UUID> plainPackageIds = inOrder.stream()
                .filter(l -> l.getVariant().isPackage() && l.getChoices().isEmpty())
                .map(l -> l.getVariant().getId()).distinct().toList();
        Map<UUID, List<PackageAvailabilityService.Part>> defaultParts = packageAvailability.partsOf(plainPackageIds);
        Map<UUID, List<PackageAvailabilityService.Part>> partsByLine = new HashMap<>();
        for (CartItem line : inOrder) {
            if (!line.getVariant().isPackage()) {
                continue;
            }
            partsByLine.put(line.getId(), line.getChoices().isEmpty()
                    ? defaultParts.getOrDefault(line.getVariant().getId(), List.of())
                    : partsOf(selections.getOrDefault(line.getId(), List.of())));
        }

        // The real variants whose shelf matters: a plain line's own, a package's parts.
        java.util.Set<UUID> shelfIds = new java.util.HashSet<>();
        for (CartItem line : inOrder) {
            if (line.getVariant().isPackage()) {
                partsByLine.getOrDefault(line.getId(), List.of()).forEach(p -> shelfIds.add(p.variant().getId()));
            } else {
                shelfIds.add(line.getVariant().getId());
            }
        }
        Map<UUID, BigDecimal> remaining = new HashMap<>(availabilityService.available(shelfIds));

        Map<UUID, BigDecimal> result = new HashMap<>();
        for (CartItem line : inOrder) {
            ProductVariant v = line.getVariant();
            if (!v.isPackage()) {
                BigDecimal left = remaining.getOrDefault(v.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
                result.put(line.getId(), left);
                remaining.put(v.getId(), left.subtract(line.getQty().min(left)));
                continue;
            }
            List<PackageAvailabilityService.Part> parts = partsByLine.getOrDefault(line.getId(), List.of());
            // Parts that draw on one variant share its shelf (see PackageAvailabilityService.wholePackages).
            BigDecimal left = PackageAvailabilityService.wholePackages(parts,
                    variantId -> remaining.getOrDefault(variantId, BigDecimal.ZERO));
            result.put(line.getId(), left);
            BigDecimal taken = line.getQty().min(left);
            for (PackageAvailabilityService.Part part : parts) {
                BigDecimal id = remaining.getOrDefault(part.variant().getId(), BigDecimal.ZERO);
                remaining.put(part.variant().getId(), id.subtract(taken.multiply(part.qty())).max(BigDecimal.ZERO));
            }
        }
        return result;
    }

    private record Refreshed(List<CartItem> items, Map<UUID, BigDecimal> changedFrom) {}

    private Refreshed refreshPrices(Cart cart) {
        // In the order they were added, so a line never moves when its quantity or price is updated.
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId()).stream()
                .sorted(CartItem.IN_ORDER_ADDED).toList();
        PromoCampaign campaign = promoCampaignService.getActiveOrNull();
        Map<UUID, BigDecimal> changedFrom = new HashMap<>();
        for (CartItem item : items) {
            EffectivePriceResolver.EffectivePrice current = currentPrice(item, campaign);
            boolean wholesaleApplied = current.source() == EffectivePriceResolver.Source.WHOLESALE;
            if (current.effectivePrice().compareTo(item.getUnitPriceSnapshot()) != 0
                    || wholesaleApplied != item.isWholesaleApplied()) {
                if (current.effectivePrice().compareTo(item.getUnitPriceSnapshot()) != 0) {
                    changedFrom.put(item.getId(), item.getUnitPriceSnapshot());
                }
                item.setUnitPriceSnapshot(current.effectivePrice());
                item.setWholesaleApplied(wholesaleApplied);
                cartItemRepository.save(item);
            }
        }
        return new Refreshed(items, changedFrom);
    }

    /**
     * Today's price for a line (a package with picks: its price plus what the picks make of it). A product that can no longer
     * be bought (unpublished, deleted, no price) keeps the price it had: there is nothing to look up, and the line is
     * reported as unavailable instead.
     */
    private EffectivePriceResolver.EffectivePrice currentPrice(CartItem item, PromoCampaign campaign) {
        ProductVariant variant = item.getVariant();
        if (item.isFree()) {
            return new EffectivePriceResolver.EffectivePrice(BigDecimal.ZERO, BigDecimal.ZERO, null,
                    EffectivePriceResolver.Source.NONE);
        }
        if (!isPurchasable(variant)) {
            return new EffectivePriceResolver.EffectivePrice(item.getUnitPriceSnapshot(), item.getUnitPriceSnapshot(),
                    null, EffectivePriceResolver.Source.NONE);
        }
        return withPicks(item, priceResolver.resolve(variant, campaign, item.getQty()));
    }
}
