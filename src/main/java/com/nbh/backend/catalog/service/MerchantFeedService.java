package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.CatalogCategoryDto;
import com.nbh.backend.catalog.dto.CatalogVariantCardDto;
import com.nbh.backend.catalog.entity.VariantBarcode;
import com.nbh.backend.catalog.repository.VariantBarcodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shop's products for Google Merchant Center (owner, 2026-11-06: Shopping ads and free listings, to be seen beside
 * Jumia). One item per option a shopper can buy, made from the same cards the storefront shows, so the price, the deal price
 * and whether it is in stock always match the product page, which Google checks. Merchant Center fetches it on its own
 * schedule; it is built at most every few minutes however often it is asked for.
 *
 * <p>Left out: packages (they have their own pages), anything without a price or a photo (Google refuses both). Shipping
 * and returns are set once in Merchant Center itself rather than on every item.
 */
@Service
public class MerchantFeedService {

    /** How long a built feed is kept before the next request builds it again. */
    static final Duration FRESH_FOR = Duration.ofMinutes(10);
    private static final int PAGE = 500;
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmxxx");

    /** One product option as the feed needs it: everything else on a card is left behind. */
    record Item(String id, String title, String description, String link, String imageLink, boolean inStock,
                BigDecimal price, BigDecimal salePrice, Instant saleEnds, String brand, String gtin, String groupId,
                String color, String size, String productType) {}

    private final CatalogService catalog;
    private final VariantBarcodeRepository barcodes;
    private final Clock clock;
    private final String site;

    private volatile String built;
    private volatile Instant builtAt = Instant.EPOCH;

    public MerchantFeedService(CatalogService catalog, VariantBarcodeRepository barcodes, Clock clock,
                               @Value("${app.merchant-feed.site-url:https://trendyshaccosmetic.com}") String site) {
        this.catalog = catalog;
        this.barcodes = barcodes;
        this.clock = clock;
        this.site = site.replaceAll("/+$", "");
    }

    /** The feed as RSS 2.0 with Google's product fields. */
    public String feed() {
        Instant now = clock.instant();
        String have = built;
        if (have != null && builtAt.plus(FRESH_FOR).isAfter(now)) return have;
        synchronized (this) {
            if (built != null && builtAt.plus(FRESH_FOR).isAfter(clock.instant())) return built;
            String fresh = render(items(), site, clock.instant());
            built = fresh;
            builtAt = clock.instant();
            return fresh;
        }
    }

    private List<Item> items() {
        Map<UUID, CatalogCategoryDto> categories = new HashMap<>();
        catalog.listCategories().forEach(c -> categories.put(c.id(), c));
        List<CatalogVariantCardDto> cards = new ArrayList<>();
        for (int page = 0; page < 200; page++) {
            Page<CatalogVariantCardDto> p = catalog.browseCards(null, null, null, null, null, PageRequest.of(page, PAGE));
            cards.addAll(p.getContent());
            if (!p.hasNext()) break;
        }
        Map<UUID, String> gtins = gtins(cards.stream().map(CatalogVariantCardDto::variantId).toList());
        List<Item> out = new ArrayList<>();
        for (CatalogVariantCardDto c : cards) {
            Item item = item(c, gtins.get(c.variantId()), productType(c.primaryCategoryId(), categories), site);
            if (item != null) out.add(item);
        }
        return out;
    }

    /** A card as a feed item, or null when Google would refuse it (a package, no price, no photo). */
    static Item item(CatalogVariantCardDto c, String gtin, String productType, String site) {
        if (c.isPackage() || c.price() == null || c.price().signum() <= 0 || blank(c.imageUrl())) return null;
        boolean sale = c.onDeal() && c.originalPrice() != null && c.originalPrice().compareTo(c.price()) > 0;
        String id = c.sku() != null && !c.sku().isBlank() && c.sku().length() <= 50 ? c.sku() : c.variantId().toString();
        String about = !blank(c.shortDescription()) ? c.shortDescription() : c.description();
        String text = plain(about);
        return new Item(id, cut(c.displayName(), 150), cut(blank(text) ? c.displayName() : text, 5000),
                site + "/product/" + c.variantId() + "/", absolute(c.imageUrl(), site), c.inStock(),
                sale ? c.originalPrice() : c.price(), sale ? c.price() : null, sale ? c.dealEndsAt() : null,
                blank(c.brandName()) ? null : cut(c.brandName(), 70), gtin,
                c.siblingCount() > 0 ? c.productId().toString() : null,
                blank(c.optionColor()) ? null : cut(c.optionColor(), 100),
                blank(c.optionSize()) ? null : cut(c.optionSize(), 100), productType);
    }

    /** "Lash supplies > Lash glue": the card's category and those above it, as the shop files it. */
    static String productType(UUID categoryId, Map<UUID, CatalogCategoryDto> categories) {
        List<String> path = new ArrayList<>();
        UUID at = categoryId;
        for (int depth = 0; at != null && depth < 6; depth++) {
            CatalogCategoryDto c = categories.get(at);
            if (c == null) break;
            path.add(0, c.name());
            at = c.parentId();
        }
        return path.isEmpty() ? null : cut(String.join(" > ", path), 750);
    }

    /** Each option's barcode where it is a real GTIN (EAN-13, UPC and the like, with a right check digit). */
    private Map<UUID, String> gtins(List<UUID> variantIds) {
        Map<UUID, String> out = new HashMap<>();
        for (int i = 0; i < variantIds.size(); i += 1000) {
            for (VariantBarcode b : barcodes.findByVariantIdIn(variantIds.subList(i, Math.min(variantIds.size(), i + 1000)))) {
                String code = b.getBarcode() == null ? "" : b.getBarcode().strip();
                if (!validGtin(code)) continue;
                UUID v = b.getVariant().getId();
                if (b.isPrimary() || !out.containsKey(v)) out.put(v, code);
            }
        }
        return out;
    }

    /** Whether this is a GTIN Google accepts: 8, 12, 13 or 14 digits whose last digit checks out. */
    static boolean validGtin(String code) {
        if (code == null || !code.matches("\\d{8}|\\d{12,14}")) return false;
        if (code.chars().allMatch(ch -> ch == '0')) return false;
        int sum = 0;
        for (int i = code.length() - 2, weight = 3; i >= 0; i--, weight = 4 - weight) sum += (code.charAt(i) - '0') * weight;
        return (10 - sum % 10) % 10 == code.charAt(code.length() - 1) - '0';
    }

    static String render(List<Item> items, String site, Instant now) {
        StringBuilder x = new StringBuilder(items.size() * 900 + 400);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<rss version=\"2.0\" xmlns:g=\"http://base.google.com/ns/1.0\">\n<channel>\n")
                .append("<title>Trendysh Accosmetic</title>\n")
                .append("<link>").append(esc(site + "/")).append("</link>\n")
                .append("<description>Nail, lash, brow, makeup and spa supplies from Trendysh Accosmetic, Nairobi.</description>\n");
        for (Item i : items) {
            x.append("<item>\n");
            tag(x, "g:id", i.id());
            tag(x, "title", i.title());
            tag(x, "description", i.description());
            tag(x, "link", i.link());
            tag(x, "g:image_link", i.imageLink());
            tag(x, "g:availability", i.inStock() ? "in_stock" : "out_of_stock");
            tag(x, "g:condition", "new");
            tag(x, "g:price", money(i.price()));
            if (i.salePrice() != null) {
                tag(x, "g:sale_price", money(i.salePrice()));
                if (i.saleEnds() != null && i.saleEnds().isAfter(now)) {
                    Instant from = now.minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
                    tag(x, "g:sale_price_effective_date",
                            WHEN.format(from.atZone(NAIROBI)) + "/" + WHEN.format(i.saleEnds().atZone(NAIROBI)));
                }
            }
            if (i.brand() != null) tag(x, "g:brand", i.brand());
            if (i.gtin() != null) tag(x, "g:gtin", i.gtin());
            // Google asks for a GTIN where a product has one; a shop's own or unbranded goods say they have none.
            if (i.gtin() == null) tag(x, "g:identifier_exists", "no");
            if (i.groupId() != null) tag(x, "g:item_group_id", i.groupId());
            if (i.color() != null) tag(x, "g:color", i.color());
            if (i.size() != null) tag(x, "g:size", i.size());
            if (i.productType() != null) tag(x, "g:product_type", i.productType());
            x.append("</item>\n");
        }
        return x.append("</channel>\n</rss>\n").toString();
    }

    private static void tag(StringBuilder x, String name, String value) {
        x.append('<').append(name).append('>').append(esc(value)).append("</").append(name).append(">\n");
    }

    static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString() + " KES";
    }

    /** XML text, with the characters XML refuses dropped (they turn up in pasted descriptions). */
    static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= 0x20 && ch != 0xFFFE && ch != 0xFFFF) out.append(ch);
                }
            }
        }
        return out.toString();
    }

    /** Text with any markup and markdown marks taken out and its spacing tidied. */
    static String plain(String s) {
        if (s == null) return null;
        String t = s.replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
                .replaceAll("\\*\\*|__|`", "")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\s*\\n\\s*", "\n")
                .strip();
        return t.isEmpty() ? null : t;
    }

    private static String absolute(String url, String site) {
        return url.startsWith("http") ? url : site + (url.startsWith("/") ? "" : "/") + url;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max - 1).strip() + "…";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
