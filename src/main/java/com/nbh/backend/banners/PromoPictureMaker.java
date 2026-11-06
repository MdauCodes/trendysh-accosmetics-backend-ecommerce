package com.nbh.backend.banners;

import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.GeminiImageClient.Image;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Makes one builder banner's picture in the background (owner, 2026-10-31), so staff can leave the page: Gemini starts
 * from the featured products' real photos (else a photo of the linked category, else any shop photo) and draws a picture
 * shaped for the banner's place with the word area left calm ({@link PromoBannerPrompts}); it is kept on Cloudinary and
 * the picture marked ready, or failed with Gemini's reason.
 */
@Slf4j
@Service
public class PromoPictureMaker {

    /** Real photos Gemini is shown, the first edited and the rest to match. */
    private static final int MAX_REFERENCES = 4;

    private final PromoBannerRepository banners;
    private final PromoBannerPictureRepository pictures;
    private final OccasionRepository occasions;
    private final GeminiImageClient images;
    private final CloudinaryUploader cloudinary;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final com.nbh.backend.imagecleanup.ImageCleanupConfig config;

    public PromoPictureMaker(PromoBannerRepository banners, PromoBannerPictureRepository pictures,
                             OccasionRepository occasions, GeminiImageClient images, CloudinaryUploader cloudinary,
                             EntityManager em, PlatformTransactionManager transactions, Clock clock,
                             com.nbh.backend.imagecleanup.ImageCleanupConfig config) {
        this.config = config;
        this.banners = banners;
        this.pictures = pictures;
        this.occasions = occasions;
        this.images = images;
        this.cloudinary = cloudinary;
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** What one picture is made from. */
    record Job(List<String> photoUrls, PromoBannerPrompts.Brief brief) {}

    @Async
    public void make(UUID pictureId) {
        makeNow(pictureId);
    }

    /** The work itself, on the caller's thread (tests call it directly). */
    void makeNow(UUID pictureId) {
        Job job;
        try {
            job = tx.execute(s -> job(pictureId));
        } catch (RuntimeException e) {
            finish(pictureId, null, "The banner could not be read: " + e.getMessage());
            return;
        }
        if (job == null) return;
        if (job.photoUrls().isEmpty()) {
            finish(pictureId, null, "There is no product photo to start from. Feature a product with a photo first.");
            return;
        }
        try {
            List<Image> refs = new ArrayList<>();
            for (String url : job.photoUrls()) refs.add(images.download(url));
            String prompt = PromoBannerPrompts.picture(job.brief());
            // The model that keeps real products and people looking real, at the banner size (4K by default, owner
            // 2026-11-06: sharp on phones, which show only a slice of a wide picture); a model that refuses the size
            // makes its usual one.
            Image picture = images.edit(config.getPoseModel(), refs.get(0), refs.subList(1, refs.size()), prompt,
                    PromoBannerPrompts.aspectRatio(job.brief().placement()), sizeOrNull(config.getBannerImageSize()));
            String url = cloudinary.upload(picture.bytes(), "promo-banners",
                    "promo-" + job.brief().placement().name().toLowerCase(Locale.ROOT) + "-" + clock.millis()).url();
            finish(pictureId, url, null, subjectSide(job.brief()));
        } catch (GeminiException e) {
            finish(pictureId, null, e.isQuotaExceeded()
                    ? "Gemini's limit for now is used up. Try again later. (" + e.getMessage() + ")" : e.getMessage());
        } catch (RuntimeException e) {
            finish(pictureId, null, "The picture could not be made: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Job job(UUID pictureId) {
        PromoBannerPicture p = pictures.findById(pictureId).orElse(null);
        if (p == null || p.getStatus() != PromoBannerPicture.Status.MAKING) return null;
        PromoBanner b = banners.findById(p.getBannerId()).orElseThrow();
        List<String> urls = new ArrayList<>();
        List<String> names = new ArrayList<>();
        java.util.LinkedHashSet<String> categories = new java.util.LinkedHashSet<>();
        // The featured products first, then the campaign's own deals (owner, 2026-11-06: a campaign has deals, not
        // featured products, and its picture was refused "no product photo").
        for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                SELECT name, url, cats FROM (
                  SELECT 0 AS src, bp.position AS pos,
                         p.name || COALESCE(' (' || NULLIF(v.option_label, '') || ')', '') AS name,
                         (SELECT i.url FROM product_image i
                           WHERE i.variant_id = v.id OR (i.variant_id IS NULL AND i.product_id = v.product_id)
                           ORDER BY (i.variant_id IS NULL), i.is_primary DESC, i.sort_order LIMIT 1) AS url,
                         (SELECT string_agg(DISTINCT c.name, ', ') FROM product_category pc JOIN category c ON c.id = pc.category_id
                           WHERE pc.product_id = p.id) AS cats
                  FROM promo_banner_product bp
                  JOIN product_variant v ON v.id = bp.variant_id
                  JOIN product p ON p.id = v.product_id
                  WHERE bp.banner_id = :b
                  UNION ALL
                  SELECT 1, d.position, p.name,
                         (SELECT i.url FROM product_image i
                           WHERE (d.variant_id IS NOT NULL AND i.variant_id = d.variant_id) OR i.product_id = d.product_id
                           ORDER BY (i.variant_id IS DISTINCT FROM d.variant_id), i.is_primary DESC, i.sort_order LIMIT 1),
                         (SELECT string_agg(DISTINCT c.name, ', ') FROM product_category pc JOIN category c ON c.id = pc.category_id
                           WHERE pc.product_id = p.id)
                  FROM campaign_deal d
                  JOIN product p ON p.id = d.product_id
                  WHERE d.banner_id = :b
                ) x ORDER BY src, pos
                """).setParameter("b", b.getId()).getResultList()) {
            if (row[1] instanceof String url && url.startsWith("http") && urls.size() < MAX_REFERENCES
                    && !urls.contains(url)) {
                urls.add(url);
                names.add((String) row[0]);
                if (row[2] instanceof String cats) {
                    for (String cat : cats.split(",")) if (!cat.isBlank()) categories.add(cat.strip());
                }
            }
        }
        if (urls.isEmpty()) {
            UUID category = b.getLinkCategoryId() != null ? b.getLinkCategoryId() : b.getCategoryId();
            String url = category == null ? null : first(em.createNativeQuery("""
                    SELECT i.url FROM product_image i
                    JOIN product p ON p.id = i.product_id AND p.is_published AND NOT p.deleted
                    JOIN product_category pc ON pc.product_id = p.id
                    JOIN category c ON c.id = pc.category_id
                    WHERE c.id = :c OR c.parent_id = :c
                    ORDER BY i.is_primary DESC, p.is_popular DESC, i.sort_order LIMIT 1
                    """).setParameter("c", category).getResultList());
            if (url != null) urls.add(url);
        }
        Occasion occasion = b.getOccasionId() == null ? null : occasions.findById(b.getOccasionId()).orElse(null);
        String theme = occasion == null ? null : occasion.getName();
        // The banner's own colour as staff set it, else its occasion's, else the shop's pink.
        CampaignDealService.Arrangement plan = CampaignDealService.readArrangement(b.getArrangement());
        PromoBannerService.Style style = PromoBannerService.readStyle(b.getStyle());
        String color = plan != null && plan.color() != null ? plan.color()
                : style != null && style.gradient() != null
                        ? String.join(" fading into ", style.gradient().colors()) + " (a blend)"
                : style != null && style.backgroundColor() != null ? style.backgroundColor()
                : occasion == null ? "#C2185B" : occasion.getColors().split(",")[0].strip();
        // Where the picture shows clearly on a campaign: the place beside the words and cards (the pose's place).
        List<String> order = plan != null && plan.laptop() != null && plan.laptop().order() != null
                ? plan.laptop().order() : List.of("pose", "words", "cards");
        String side = order.get(0).equals("pose") ? "LEFT" : order.get(order.size() - 1).equals("pose") ? "RIGHT" : "CENTRE";
        Number dealCount = (Number) em.createNativeQuery("SELECT count(*) FROM campaign_deal WHERE banner_id = :b")
                .setParameter("b", b.getId()).getSingleResult();
        String deals = dealsLine(b.getId());
        String idea = java.util.stream.Stream.of(b.getHeadline(), b.getSubline())
                .filter(s -> s != null && !s.isBlank()).collect(java.util.stream.Collectors.joining(" — "));
        // this banner's how-many-th AI picture, so the pictures of one request each get a different look
        int variant = (int) pictures.findByBannerIdOrderByCreatedAtDesc(b.getId()).stream()
                .filter(x -> x.getSource() == PromoBannerPicture.Source.AI && x.getCreatedAt() != null
                        && p.getCreatedAt() != null && x.getCreatedAt().isBefore(p.getCreatedAt()))
                .count();
        return new Job(urls, new PromoBannerPrompts.Brief(b.getPlacement(), names, List.copyOf(categories), deals,
                idea.isBlank() ? null : idea, theme, color, side, dealCount.longValue() > 0, p.getBrief(), variant));
    }

    /** The campaign's deals in a few words ("up to 20% off; buy 5 get 3 free"), for the mood only. */
    @SuppressWarnings("unchecked")
    private String dealsLine(UUID bannerId) {
        List<String> parts = new ArrayList<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                SELECT deal_type, max(percent_off), max(amount_off), max(buy_qty), max(free_qty)
                FROM campaign_deal WHERE banner_id = :b GROUP BY deal_type
                """).setParameter("b", bannerId).getResultList()) {
            String type = String.valueOf(row[0]);
            if (type.equals("PERCENT") && row[1] != null) parts.add("up to " + ((Number) row[1]).intValue() + "% off");
            else if (type.equals("AMOUNT") && row[2] != null) parts.add("up to KSh " + ((Number) row[2]).intValue() + " off");
            else if (type.equals("BUY_GET") && row[3] != null) parts.add("buy " + row[3] + " get " + row[4] + " free");
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static String first(List<?> rows) {
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
    }

    private void finish(UUID pictureId, String url, String failure) {
        finish(pictureId, url, failure, null);
    }

    private void finish(UUID pictureId, String url, String failure, String subjectSide) {
        if (failure != null) log.warn("Banner picture {} failed: {}", pictureId, failure);
        tx.executeWithoutResult(s -> pictures.findById(pictureId).ifPresent(p -> {
            p.setStatus(url != null ? PromoBannerPicture.Status.READY : PromoBannerPicture.Status.FAILED);
            p.setImageUrl(url);
            p.setNote(failure);
            p.setSubjectSide(url != null ? subjectSide : null);
        }));
    }

    /**
     * Where the prompt put the subject of a wide (home or strip) picture, so the phone can show it (PromoBannerPrompts.layout:
     * the deals' side for a campaign with cards, else the right). Null for the other places, whose subject is centred.
     */
    private static String sizeOrNull(String size) {
        return size == null || size.isBlank() ? null : size.strip();
    }

    static String subjectSide(PromoBannerPrompts.Brief b) {
        if (b.placement() != PromoBanner.Placement.HERO && b.placement() != PromoBanner.Placement.STRIP) return null;
        if (!b.hasCards()) return "RIGHT";
        return b.heroSide() == null ? "RIGHT" : b.heroSide();
    }
}
