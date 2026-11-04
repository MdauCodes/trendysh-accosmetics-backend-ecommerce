package com.nbh.backend.banners;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.LiveDeals;
import com.nbh.backend.catalog.service.VariantCovers;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.stock.service.StockAvailabilityService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Campaign deals (owner, 2026-11-04). A campaign is a builder banner; its deals are products (or one option of a product)
 * at a % off, an amount off each piece, or "buy X get Y free", and they are the cards on the banner.
 *
 * <p>A deal is live while its campaign is approved and inside its dates, its own end (if any) has not passed, there is
 * stock to sell under it (for buy X get Y: a whole X+Y of the product, since the free pieces must be there too), and its
 * cap ("first 20 pieces") is not used up. A campaign that has deals is live only while at least one of them is: a
 * "while stock lasts" campaign ends itself when its last deal sells out.
 *
 * <p>What is live is worked out at most every few seconds and kept in memory, because every price the shop shows asks
 * ({@link LiveDeals}); saving a campaign, and any stock or catalogue change, forgets it at once.
 */
@Service
public class CampaignDealService implements LiveDeals {

    /** How long what is live is remembered. */
    private static final long MEMORY_MS = 20_000;
    /** "Only N left" is said at this many or fewer (the basket's low-stock figure). */
    static final int FEW_LEFT = 5;
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneId.of("Africa/Nairobi"));

    private final CampaignDealRepository deals;
    private final PromoBannerRepository banners;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final StockAvailabilityService availability;
    private final VariantCovers covers;
    private final ApplicationEventPublisher events;
    /** The owner's poses (set by Spring; may be left out of hand-built instances). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OwnerPoseService poses;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    private volatile Snapshot snapshot;

    public CampaignDealService(CampaignDealRepository deals, PromoBannerRepository banners, ProductRepository products,
                               ProductVariantRepository variants, StockAvailabilityService availability,
                               VariantCovers covers, ApplicationEventPublisher events,
                               PlatformTransactionManager transactions, Clock clock) {
        this.deals = deals;
        this.banners = banners;
        this.products = products;
        this.variants = variants;
        this.availability = availability;
        this.covers = covers;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- shapes

    /** One deal as staff set it. {@code variantId} null = every option of the product. */
    public record DealInput(UUID productId, UUID variantId, String type, BigDecimal percentOff, BigDecimal amountOff,
                            Integer buyQty, Integer freeQty, Integer capQty, Instant endsAt) {}

    /**
     * A campaign's own settings and its deals, saved together (the deals replace the old ones). {@code showAs}:
     * PRODUCTS, CATEGORIES or AUTO; {@code slug} null or blank = made from the title.
     */
    public record CampaignEdit(String title, String slug, String showAs, Boolean wholesaleApplies,
                               List<DealInput> deals, UUID poseId, Boolean removePose, Arrangement arrangement) {}

    /**
     * Where she stands, as a share of her place on the banner, and how big (1 is the usual size). Kept within limits so she
     * can't be lost off the banner.
     */
    public record PosePlace(Double x, Double y, Double scale) {
        PosePlace tidy() {
            return new PosePlace(within(x, -120, 120, 0), within(y, -60, 60, 0), within(scale, 0.5, 1.8, 1));
        }

        private static double within(Double v, double min, double max, double otherwise) {
            return v == null || v.isNaN() ? otherwise : Math.max(min, Math.min(max, v));
        }
    }

    /** A laptop banner: its three parts from left to right ("pose", "words", "cards"), and her place. */
    public record LaptopPlan(List<String> order, PosePlace pose) {}

    /** A phone banner: which side she stands, whether the cards come above her, and her place. */
    public record PhonePlan(String poseSide, Boolean cardsFirst, PosePlace pose) {}

    /** How staff arranged a banner (owner, 2026-11-04): its colour, whether she is mirrored, and each device's plan. */
    public record Arrangement(String color, Boolean flip, LaptopPlan laptop, PhonePlan phone) {}

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Only what the shop knows how to draw; null when nothing is left (the usual arrangement). */
    static Arrangement tidy(Arrangement in) {
        if (in == null) return null;
        String color = in.color() != null && in.color().matches("^#[0-9a-fA-F]{6}$") ? in.color() : null;
        LaptopPlan laptop = null;
        if (in.laptop() != null) {
            List<String> order = in.laptop().order();
            boolean good = order != null && order.size() == 3 && new HashSet<>(order).equals(Set.of("pose", "words", "cards"));
            laptop = new LaptopPlan(good ? List.copyOf(order) : null,
                    in.laptop().pose() == null ? null : in.laptop().pose().tidy());
        }
        PhonePlan phone = null;
        if (in.phone() != null) {
            String side = "left".equals(in.phone().poseSide()) || "right".equals(in.phone().poseSide()) ? in.phone().poseSide() : null;
            phone = new PhonePlan(side, in.phone().cardsFirst(), in.phone().pose() == null ? null : in.phone().pose().tidy());
        }
        Boolean flip = Boolean.TRUE.equals(in.flip()) ? Boolean.TRUE : null;
        return color == null && flip == null && laptop == null && phone == null ? null : new Arrangement(color, flip, laptop, phone);
    }

    private static String writeArrangement(Arrangement a) {
        try {
            return a == null ? null : JSON.writeValueAsString(a);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Arrangement readArrangement(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, Arrangement.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * A deal as staff see it. {@code state}: LIVE, WAITING (its campaign is not live yet), SOLD_OUT, CAP_REACHED or ENDED;
     * {@code price}/{@code dealPrice} for the option shown (dealPrice equals price for buy X get Y).
     */
    public record DealAdminView(UUID id, UUID productId, String productName, UUID variantId, String optionLabel,
                                String imageUrl, String type, BigDecimal percentOff, BigDecimal amountOff,
                                Integer buyQty, Integer freeQty, Integer capQty, Instant endsAt, int position,
                                BigDecimal price, BigDecimal dealPrice, BigDecimal available, BigDecimal sold,
                                String state) {}

    /** A deal card the shop draws: the product, the option shown, its prices, what is left and its category. */
    public record DealCard(UUID dealId, String type, BigDecimal percentOff, BigDecimal amountOff, Integer buyQty,
                           Integer freeQty, Instant endsAt, Integer left, UUID productId, String productSlug,
                           String productName, UUID variantId, String imageUrl, List<UUID> categoryIds,
                           BigDecimal price, BigDecimal dealPrice) {}

    /** The campaign side of a banner, for staff. */
    public record CampaignAdmin(String title, String slug, String showAs, boolean wholesaleApplies,
                                List<DealAdminView> deals, OwnerPoseService.PoseView pose, Arrangement arrangement) {}

    /** The campaign side of a banner, for the shop: its live deals only. */
    public record CampaignPublic(String title, String slug, String showAs, boolean hasDeals, List<DealCard> deals,
                                 OwnerPoseService.PoseView pose, Arrangement arrangement) {}

    // ---------------------------------------------------------------- what is live

    /** One live deal, with what its cap still allows (null: no cap). */
    private record Live(CampaignDeal deal, PromoBanner banner, Instant endsAt, BigDecimal capLeft) {}

    /**
     * What is live now: deals by product, what each option can sell, the banners with at least one live deal and the
     * banners that have deals at all (live or not).
     */
    private record Snapshot(Map<UUID, List<Live>> byProduct, Map<UUID, BigDecimal> available, Set<UUID> bannersLive,
                            Set<UUID> bannersWithDeals, long at) {}

    @Override
    public LiveDeal dealFor(ProductVariant variant) {
        if (variant == null || variant.getProduct() == null) return null;
        Snapshot s = current();
        List<Live> mine = s.byProduct().get(variant.getProduct().getId());
        if (mine == null) return null;
        Live pick = null;
        for (Live l : mine) {
            if (variant.getId().equals(l.deal().getVariantId())) {
                pick = l;
                break;
            }
            if (l.deal().getVariantId() == null && pick == null) pick = l;
        }
        if (pick == null) return null;
        return liveDeal(pick, s.available().getOrDefault(variant.getId(), BigDecimal.ZERO));
    }

    /** Whether this banner has any deal at all, live or not (staff's view; asks the database). */
    boolean hasAnyDeal(UUID bannerId) {
        return deals.existsByBannerId(bannerId);
    }

    /** Whether this LIVE banner has deals, and whether at least one of them is live now. */
    boolean hasDeals(UUID bannerId) {
        return current().bannersWithDeals().contains(bannerId);
    }

    boolean hasLiveDeal(UUID bannerId) {
        return current().bannersLive().contains(bannerId);
    }

    private LiveDeal liveDeal(Live l, BigDecimal optionAvailable) {
        BigDecimal left = l.capLeft() == null ? optionAvailable : optionAvailable.min(l.capLeft());
        Integer few = left.compareTo(BigDecimal.valueOf(FEW_LEFT)) <= 0 ? Math.max(0, left.intValue()) : null;
        PromoBanner b = l.banner();
        CampaignDeal d = l.deal();
        return new LiveDeal(d.getId(), b.getId(), b.getSlug(), title(b), d.getType().name(), d.getPercentOff(),
                d.getAmountOff(), d.getBuyQty(), d.getFreeQty(), l.endsAt(), b.isWholesaleApplies(), few);
    }

    static String title(PromoBanner b) {
        return b.getTitle() != null && !b.getTitle().isBlank() ? b.getTitle() : b.getName();
    }

    /** Forgets what is live, so the next price looked up sees a change at once. */
    public void forget() {
        snapshot = null;
    }

    /** Stock and the catalogue move what is live (a deal sells out, a product is unpublished). */
    @EventListener
    public void onDataChanged(DataChangedEvent event) {
        if (event.topic() == DataChangedEvent.Topic.STOCK || event.topic() == DataChangedEvent.Topic.CATALOG
                || event.topic() == DataChangedEvent.Topic.PRODUCTS) {
            forget();
        }
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        if (s == null || System.currentTimeMillis() - s.at() > MEMORY_MS) {
            s = readTx.execute(t -> compute());
            snapshot = s;
        }
        return s;
    }

    private Snapshot compute() {
        Instant now = clock.instant();
        List<PromoBanner> liveBanners = banners.findLive(now);
        Map<UUID, PromoBanner> byId = new HashMap<>();
        liveBanners.forEach(b -> byId.put(b.getId(), b));
        List<CampaignDeal> all = byId.isEmpty() ? List.of() : deals.findByBannerIdInOrderByPositionAsc(byId.keySet());
        Set<UUID> withDeals = new HashSet<>();
        all.forEach(d -> withDeals.add(d.getBannerId()));

        List<CampaignDeal> inDates = all.stream()
                .filter(d -> d.getEndsAt() == null || d.getEndsAt().isAfter(now)).toList();
        Map<UUID, BigDecimal> sold = sold(inDates.stream().map(CampaignDeal::getId).toList());
        Set<UUID> productIds = new HashSet<>();
        inDates.forEach(d -> productIds.add(d.getProductId()));
        Map<UUID, List<ProductVariant>> sellable = sellableByProduct(productIds);
        Set<UUID> variantIds = new HashSet<>();
        sellable.values().forEach(vs -> vs.forEach(v -> variantIds.add(v.getId())));
        Map<UUID, BigDecimal> available = new HashMap<>(availability.available(variantIds));

        Map<UUID, List<Live>> byProduct = new HashMap<>();
        Set<UUID> bannersLive = new HashSet<>();
        for (CampaignDeal d : inDates) {
            PromoBanner b = byId.get(d.getBannerId());
            List<ProductVariant> options = sellable.getOrDefault(d.getProductId(), List.of()).stream()
                    .filter(v -> d.getVariantId() == null || d.getVariantId().equals(v.getId())).toList();
            if (options.isEmpty()) continue; // unpublished, deleted, or the option is gone
            BigDecimal capLeft = d.getCapQty() == null ? null
                    : BigDecimal.valueOf(d.getCapQty()).subtract(sold.getOrDefault(d.getId(), BigDecimal.ZERO));
            if (capLeft != null && capLeft.signum() <= 0) continue;
            BigDecimal stock = options.stream().map(v -> available.getOrDefault(v.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal needed = d.getType() == CampaignDeal.Type.BUY_GET
                    ? BigDecimal.valueOf((long) d.getBuyQty() + d.getFreeQty()) : BigDecimal.ONE;
            if (stock.compareTo(needed) < 0) continue;
            if (capLeft != null && capLeft.compareTo(needed) < 0) continue;
            Instant endsAt = earlier(d.getEndsAt(), b.getEndsAt());
            byProduct.computeIfAbsent(d.getProductId(), k -> new ArrayList<>()).add(new Live(d, b, endsAt, capLeft));
            bannersLive.add(b.getId());
        }
        return new Snapshot(byProduct, available, bannersLive, withDeals, System.currentTimeMillis());
    }

    private static Instant earlier(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private Map<UUID, BigDecimal> sold(Collection<UUID> dealIds) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        if (dealIds.isEmpty()) return out;
        for (Object[] row : deals.soldUnderDeals(dealIds)) {
            out.put(UUID.fromString((String) row[0]), new BigDecimal(row[1].toString()));
        }
        return out;
    }

    /** Each product's options that can be sold (published, not deleted, priced, active), in their own order. */
    private Map<UUID, List<ProductVariant>> sellableByProduct(Collection<UUID> productIds) {
        Map<UUID, List<ProductVariant>> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        for (ProductVariant v : variants.findByProductIdInAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNull(productIds)) {
            Product p = v.getProduct();
            if (!p.isPublished() || p.isDeleted() || p.isMockData() || v.isPackage()) continue;
            out.computeIfAbsent(p.getId(), k -> new ArrayList<>()).add(v);
        }
        out.values().forEach(l -> l.sort(Comparator.comparingInt(ProductVariant::getSortOrder)));
        return out;
    }

    // ---------------------------------------------------------------- the shop

    /** A banner's campaign side for the shop: its live deals as cards, in staff's order. */
    public Map<UUID, CampaignPublic> forShop(List<PromoBanner> shown) {
        Map<UUID, CampaignPublic> out = new LinkedHashMap<>();
        if (shown.isEmpty()) return out;
        Snapshot s = current();
        Map<UUID, List<Live>> byBanner = new HashMap<>();
        s.byProduct().values().forEach(ls -> ls.forEach(l -> byBanner.computeIfAbsent(l.banner().getId(), k -> new ArrayList<>()).add(l)));
        Set<UUID> productIds = new HashSet<>();
        for (PromoBanner b : shown) {
            byBanner.getOrDefault(b.getId(), List.of()).forEach(l -> productIds.add(l.deal().getProductId()));
        }
        Map<UUID, List<ProductVariant>> sellable = productIds.isEmpty() ? Map.of() : sellableByProduct(productIds);
        List<ProductVariant> shownOptions = new ArrayList<>();
        Map<UUID, ProductVariant> optionByDeal = new HashMap<>();
        for (PromoBanner b : shown) {
            for (Live l : byBanner.getOrDefault(b.getId(), List.of())) {
                ProductVariant v = shownOption(l.deal(), sellable.getOrDefault(l.deal().getProductId(), List.of()), s.available());
                if (v != null) {
                    optionByDeal.put(l.deal().getId(), v);
                    shownOptions.add(v);
                }
            }
        }
        Map<UUID, String> photos = covers.urlsFor(shownOptions);
        for (PromoBanner b : shown) {
            List<DealCard> cards = byBanner.getOrDefault(b.getId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(l -> l.deal().getPosition()))
                    .map(l -> {
                        ProductVariant v = optionByDeal.get(l.deal().getId());
                        if (v == null) return null;
                        LiveDeal ld = liveDeal(l, l.deal().getVariantId() != null
                                ? s.available().getOrDefault(v.getId(), BigDecimal.ZERO)
                                : sellable.getOrDefault(l.deal().getProductId(), List.of()).stream()
                                        .map(o -> s.available().getOrDefault(o.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO))
                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                        Product p = v.getProduct();
                        BigDecimal price = v.getSellingPrice();
                        return new DealCard(l.deal().getId(), ld.type(), ld.percentOff(), ld.amountOff(), ld.buyQty(),
                                ld.freeQty(), ld.endsAt(), ld.left(), p.getId(), p.getSlug(), p.getName(),
                                v.getId(), photos.get(v.getId()),
                                p.getCategories().stream().map(Category::getId).toList(), price, ld.priceOf(price));
                    })
                    .filter(Objects::nonNull).toList();
            out.put(b.getId(), new CampaignPublic(title(b), b.getSlug(), b.getShowAs(),
                    s.bannersWithDeals().contains(b.getId()), cards, shopPose(b), readArrangement(b.getArrangement())));
        }
        return out;
    }

    /** The banner's pose for the shop: only an approved one shows. */
    private OwnerPoseService.PoseView shopPose(PromoBanner b) {
        OwnerPose p = poses == null ? null : poses.findOrNull(b.getPoseId());
        return p == null || p.getStatus() != OwnerPose.Status.APPROVED ? null : OwnerPoseService.view(p);
    }

    /** The option a product-wide deal shows: its first in stock (else its first); a one-option deal, that option. */
    private static ProductVariant shownOption(CampaignDeal d, List<ProductVariant> options, Map<UUID, BigDecimal> available) {
        if (d.getVariantId() != null) {
            return options.stream().filter(v -> v.getId().equals(d.getVariantId())).findFirst().orElse(null);
        }
        return options.stream().filter(v -> available.getOrDefault(v.getId(), BigDecimal.ZERO).signum() > 0)
                .findFirst().orElse(options.isEmpty() ? null : options.get(0));
    }

    // ---------------------------------------------------------------- staff

    /** A banner's campaign side for staff: its settings and every deal with how it stands. */
    public CampaignAdmin forStaff(PromoBanner b) {
        return readTx.execute(t -> {
            List<CampaignDeal> mine = deals.findByBannerIdOrderByPositionAsc(b.getId());
            Snapshot s = current();
            Set<UUID> productIds = new HashSet<>();
            mine.forEach(d -> productIds.add(d.getProductId()));
            Map<UUID, Product> productById = new HashMap<>();
            products.findAllById(productIds).forEach(p -> productById.put(p.getId(), p));
            Map<UUID, List<ProductVariant>> sellable = sellableByProduct(productIds);
            Set<UUID> optionIds = new HashSet<>();
            sellable.values().forEach(l -> l.forEach(v -> optionIds.add(v.getId())));
            Map<UUID, BigDecimal> available = availability.available(optionIds);
            Map<UUID, BigDecimal> sold = sold(mine.stream().map(CampaignDeal::getId).toList());
            List<ProductVariant> shownOptions = new ArrayList<>();
            Map<UUID, ProductVariant> optionByDeal = new HashMap<>();
            for (CampaignDeal d : mine) {
                ProductVariant v = shownOption(d, sellable.getOrDefault(d.getProductId(), List.of()), available);
                if (v != null) {
                    optionByDeal.put(d.getId(), v);
                    shownOptions.add(v);
                }
            }
            Map<UUID, String> photos = covers.urlsFor(shownOptions);
            Instant now = clock.instant();
            boolean campaignLive = "LIVE".equals(PromoBannerService.state(b, now));
            List<DealAdminView> views = mine.stream().map(d -> {
                ProductVariant v = optionByDeal.get(d.getId());
                Product p = productById.get(d.getProductId());
                List<ProductVariant> options = sellable.getOrDefault(d.getProductId(), List.of()).stream()
                        .filter(o -> d.getVariantId() == null || d.getVariantId().equals(o.getId())).toList();
                BigDecimal stock = options.stream().map(o -> available.getOrDefault(o.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal soldHere = sold.getOrDefault(d.getId(), BigDecimal.ZERO);
                BigDecimal price = v == null ? null : v.getSellingPrice();
                BigDecimal dealPrice = price == null ? null : priceUnder(d, price);
                String state;
                if (d.getEndsAt() != null && !d.getEndsAt().isAfter(now)) state = "ENDED";
                else if (d.getCapQty() != null && soldHere.compareTo(BigDecimal.valueOf(d.getCapQty())) >= 0) state = "CAP_REACHED";
                else if (stock.compareTo(d.getType() == CampaignDeal.Type.BUY_GET
                        ? BigDecimal.valueOf((long) d.getBuyQty() + d.getFreeQty()) : BigDecimal.ONE) < 0) state = "SOLD_OUT";
                else state = campaignLive && s.byProduct().getOrDefault(d.getProductId(), List.of()).stream()
                        .anyMatch(l -> l.deal().getId().equals(d.getId())) ? "LIVE" : "WAITING";
                return new DealAdminView(d.getId(), d.getProductId(), p == null ? "(product removed)" : p.getName(),
                        d.getVariantId(), d.getVariantId() == null || v == null ? null : v.shortOption(),
                        v == null ? null : photos.get(v.getId()), d.getType().name(), d.getPercentOff(), d.getAmountOff(),
                        d.getBuyQty(), d.getFreeQty(), d.getCapQty(), d.getEndsAt(), d.getPosition(), price, dealPrice,
                        stock, soldHere, state);
            }).toList();
            OwnerPose pose = poses == null ? null : poses.findOrNull(b.getPoseId());
            return new CampaignAdmin(b.getTitle(), b.getSlug(), b.getShowAs(), b.isWholesaleApplies(), views,
                    pose == null ? null : OwnerPoseService.view(pose), readArrangement(b.getArrangement()));
        });
    }

    private static BigDecimal priceUnder(CampaignDeal d, BigDecimal price) {
        return new LiveDeal(d.getId(), d.getBannerId(), null, null, d.getType().name(), d.getPercentOff(),
                d.getAmountOff(), d.getBuyQty(), d.getFreeQty(), null, false, null).priceOf(price);
    }

    /**
     * Saves a campaign's settings and replaces its deals. Refused (with what to do, in staff's words) when a deal makes no
     * sense, a product is in it twice, or a product is already in another approved campaign whose dates overlap.
     */
    public CampaignAdmin save(UUID bannerId, CampaignEdit in) {
        PromoBanner saved = tx.execute(t -> {
            PromoBanner b = banners.findById(bannerId).orElseThrow(() -> new EntityNotFoundException("No banner " + bannerId));
            if (in.title() != null) b.setTitle(blankToNull(in.title(), 60));
            if (in.showAs() != null) b.setShowAs(showAs(in.showAs()));
            if (in.wholesaleApplies() != null) b.setWholesaleApplies(in.wholesaleApplies());
            if (in.arrangement() != null) b.setArrangement(writeArrangement(tidy(in.arrangement())));
            if (Boolean.TRUE.equals(in.removePose())) b.setPoseId(null);
            else if (in.poseId() != null && poses != null) b.setPoseId(poses.approved(in.poseId()).getId());
            String slug = in.slug() == null || in.slug().isBlank()
                    ? (b.getSlug() != null ? b.getSlug() : slugOf(CampaignDealService.title(b)))
                    : slugOf(in.slug());
            b.setSlug(uniqueSlug(slug, b.getId()));
            if (in.deals() != null) {
                List<CampaignDeal> next = checked(b, in.deals());
                requireNoClash(b, next);
                deals.deleteAll(deals.findByBannerIdOrderByPositionAsc(b.getId()));
                deals.flush();
                deals.saveAll(next);
            }
            // Like any banner edit, a change to what customers see is approved again before it shows.
            if (b.getStatus() != PromoBanner.Status.DRAFT) {
                b.setStatus(PromoBanner.Status.DRAFT);
                b.setApprovedBy(null);
            }
            return banners.save(b);
        });
        forget();
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
        return forStaff(saved);
    }

    private static String showAs(String in) {
        String v = in.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTO", "PRODUCTS", "CATEGORIES").contains(v)) {
            throw new UnprocessableException("Show the cards as products, as categories, or let the shop choose.");
        }
        return v;
    }

    private List<CampaignDeal> checked(PromoBanner b, List<DealInput> inputs) {
        Set<UUID> productIds = new HashSet<>();
        inputs.forEach(i -> {
            if (i.productId() == null) throw new UnprocessableException("Every deal needs a product.");
            productIds.add(i.productId());
        });
        Map<UUID, Product> productById = new HashMap<>();
        products.findAllById(productIds).forEach(p -> productById.put(p.getId(), p));
        Map<UUID, List<ProductVariant>> sellable = sellableByProduct(productIds);
        // Per product: the options with a deal of their own; a deal for the whole product is kept as the key null.
        Map<UUID, Set<UUID>> taken = new HashMap<>();
        List<CampaignDeal> out = new ArrayList<>();
        int position = 0;
        for (DealInput i : inputs) {
            Product p = productById.get(i.productId());
            if (p == null || p.isDeleted()) throw new UnprocessableException("A product in this campaign no longer exists.");
            List<ProductVariant> options = sellable.getOrDefault(p.getId(), List.of()).stream()
                    .filter(v -> i.variantId() == null || i.variantId().equals(v.getId())).toList();
            if (i.variantId() != null && options.isEmpty()) {
                throw new UnprocessableException("The option chosen for " + p.getName() + " can't be sold. Choose another.");
            }
            Set<UUID> already = taken.computeIfAbsent(p.getId(), k -> new HashSet<>());
            // Twice is the same option twice, the whole product twice, or the whole product beside one of its options.
            boolean twice = i.variantId() == null ? !already.isEmpty() : already.contains(null) || already.contains(i.variantId());
            if (twice) {
                throw new UnprocessableException(p.getName() + " is in this campaign twice. Keep one deal for it.");
            }
            already.add(i.variantId());
            CampaignDeal d = new CampaignDeal();
            d.setBannerId(b.getId());
            d.setProductId(p.getId());
            d.setVariantId(i.variantId());
            d.setPosition(position++);
            d.setEndsAt(i.endsAt());
            if (i.capQty() != null) {
                if (i.capQty() < 1) throw new UnprocessableException("A limit for " + p.getName() + " must be at least 1 piece.");
                d.setCapQty(i.capQty());
            }
            String type = i.type() == null ? "" : i.type().trim().toUpperCase(Locale.ROOT);
            switch (type) {
                case "PERCENT" -> {
                    if (i.percentOff() == null || i.percentOff().compareTo(BigDecimal.ONE) < 0
                            || i.percentOff().compareTo(BigDecimal.valueOf(95)) > 0) {
                        throw new UnprocessableException("The % off for " + p.getName() + " must be between 1 and 95.");
                    }
                    d.setType(CampaignDeal.Type.PERCENT);
                    d.setPercentOff(i.percentOff());
                }
                case "AMOUNT" -> {
                    BigDecimal cheapest = options.stream().map(ProductVariant::getSellingPrice)
                            .min(Comparator.naturalOrder()).orElse(null);
                    if (i.amountOff() == null || i.amountOff().signum() <= 0) {
                        throw new UnprocessableException("Say how many shillings off " + p.getName() + ".");
                    }
                    if (cheapest != null && i.amountOff().compareTo(cheapest) >= 0) {
                        throw new UnprocessableException("KSh " + i.amountOff().stripTrailingZeros().toPlainString()
                                + " off is more than " + p.getName() + " costs (KSh "
                                + cheapest.stripTrailingZeros().toPlainString() + "). Take less off.");
                    }
                    d.setType(CampaignDeal.Type.AMOUNT);
                    d.setAmountOff(i.amountOff());
                }
                case "BUY_GET" -> {
                    if (i.buyQty() == null || i.buyQty() < 1 || i.buyQty() > 100
                            || i.freeQty() == null || i.freeQty() < 1 || i.freeQty() > 100) {
                        throw new UnprocessableException("Say how many to buy and how many come free for " + p.getName()
                                + " (1 to 100 each).");
                    }
                    d.setType(CampaignDeal.Type.BUY_GET);
                    d.setBuyQty(i.buyQty());
                    d.setFreeQty(i.freeQty());
                }
                default -> throw new UnprocessableException("Choose % off, KSh off, or buy and get free for " + p.getName() + ".");
            }
            out.add(d);
        }
        return out;
    }

    /**
     * A product can be in only one campaign at a time (owner: never discounted twice): refused when it is in another
     * approved campaign that has not ended and whose dates overlap this one's. A campaign without dates yet counts as
     * from now on.
     */
    void requireNoClash(PromoBanner b, List<CampaignDeal> mine) {
        if (mine.isEmpty()) return;
        Instant now = clock.instant();
        Instant from = b.getStartsAt() == null ? now : b.getStartsAt();
        Instant to = b.getEndsAt();
        Map<UUID, CampaignDeal> byProduct = new HashMap<>();
        mine.forEach(d -> byProduct.put(d.getProductId(), d));
        for (CampaignDeal other : deals.findByProductIdIn(byProduct.keySet())) {
            if (other.getBannerId().equals(b.getId())) continue;
            if (other.getEndsAt() != null && !other.getEndsAt().isAfter(now)) continue;
            PromoBanner ob = banners.findById(other.getBannerId()).orElse(null);
            if (ob == null || ob.getStatus() != PromoBanner.Status.APPROVED) continue;
            if (ob.getEndsAt() != null && !ob.getEndsAt().isAfter(now)) continue;
            Instant oFrom = ob.getStartsAt() == null ? now : ob.getStartsAt();
            Instant oTo = earlier(other.getEndsAt(), ob.getEndsAt());
            boolean overlap = (to == null || oFrom.isBefore(to)) && (oTo == null || from.isBefore(oTo));
            if (!overlap) continue;
            String name = products.findById(other.getProductId()).map(Product::getName).orElse("A product");
            String when = DAY.format(oFrom) + (oTo == null ? " while stock lasts" : " to " + DAY.format(oTo));
            throw new ConflictException(name + " is already in \"" + title(ob) + "\" (" + when + "). Take it out there"
                    + " first, or give this campaign other dates.");
        }
    }

    /** Before a campaign is approved: none of its products may be in another approved campaign at the same time. */
    void requireNoClash(PromoBanner b) {
        requireNoClash(b, deals.findByBannerIdOrderByPositionAsc(b.getId()));
    }

    /** A copy of a campaign's deals for a duplicated banner. */
    void copyDeals(UUID fromBannerId, UUID toBannerId) {
        List<CampaignDeal> copies = new ArrayList<>();
        for (CampaignDeal d : deals.findByBannerIdOrderByPositionAsc(fromBannerId)) {
            CampaignDeal c = new CampaignDeal();
            c.setBannerId(toBannerId);
            c.setProductId(d.getProductId());
            c.setVariantId(d.getVariantId());
            c.setType(d.getType());
            c.setPercentOff(d.getPercentOff());
            c.setAmountOff(d.getAmountOff());
            c.setBuyQty(d.getBuyQty());
            c.setFreeQty(d.getFreeQty());
            c.setCapQty(d.getCapQty());
            c.setEndsAt(d.getEndsAt());
            c.setPosition(d.getPosition());
            copies.add(c);
        }
        deals.saveAll(copies);
    }

    private static String blankToNull(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** "Weekend Beauty Challenge!" -> "weekend-beauty-challenge". */
    static String slugOf(String text) {
        String s = (text == null ? "" : text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (s.length() > 70) s = s.substring(0, 70).replaceAll("-+$", "");
        return s.isEmpty() ? "campaign" : s;
    }

    private String uniqueSlug(String base, UUID bannerId) {
        String slug = base;
        int n = 2;
        while (true) {
            boolean taken = banners.findBySlug(slug).filter(o -> !o.getId().equals(bannerId)).isPresent();
            if (!taken) return slug;
            slug = base + "-" + n++;
        }
    }
}
