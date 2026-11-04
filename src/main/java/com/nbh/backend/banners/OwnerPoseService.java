package com.nbh.backend.banners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.GeminiImageClient;
import com.nbh.backend.imagecleanup.GeminiImageClient.GeminiException;
import com.nbh.backend.imagecleanup.GeminiImageClient.Image;
import com.nbh.backend.imagecleanup.GeminiTextClient;
import com.nbh.backend.imagecleanup.ImageCleanupConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The owner's face on the banners (owner, 2026-11-04). Staff keep a few real photos of her ({@link OwnerPhoto}); Gemini
 * makes poses from them ({@link OwnerPosePrompts}: a mood, how much of her, which side of the banner she stands on, her
 * outfit, a flat backdrop) in the background, since each costs and takes a while. Each finished pose is measured: its
 * backdrop's edge colour (what the banner fills with, so she blends in) and, asked of Gemini's text model, where her face,
 * hands and eyes are ({@link Layout}). A person approves a pose before any banner may show it: the likeness can drift.
 * Approved poses are kept and reused by every campaign.
 */
@Slf4j
@Service
public class OwnerPoseService {

    /** Photos shown to Gemini for one pose (the 4 Nov trial used three). */
    static final int MAX_REFERENCES = 3;
    /** Poses one request may make. */
    static final int MAX_PER_REQUEST = OwnerPosePrompts.VARIANTS;
    /** How long one setting's own words may be. */
    private static final int MAX_WORDS = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OwnerPhotoRepository photos;
    private final OwnerPoseRepository poses;
    private final GeminiImageClient images;
    private final CloudinaryUploader cloudinary;
    private final ImageCleanupConfig config;
    private final TransactionTemplate tx;
    private final Clock clock;
    @Autowired(required = false)
    private GeminiTextClient text;
    /** This service through Spring, so the background work runs on another thread. */
    @Autowired
    @Lazy
    private OwnerPoseService self;

    public OwnerPoseService(OwnerPhotoRepository photos, OwnerPoseRepository poses, GeminiImageClient images,
                            CloudinaryUploader cloudinary, ImageCleanupConfig config,
                            PlatformTransactionManager transactions, Clock clock) {
        this.photos = photos;
        this.poses = poses;
        this.images = images;
        this.cloudinary = cloudinary;
        this.config = config;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- shapes

    /** A box as fractions of the picture (0 to 1 from its top-left corner). */
    public record Box(double x, double y, double w, double h) {}

    public record Point(double x, double y) {}

    /**
     * How to place and crop a pose (the generator's layout contract): {@code figureSide} where she stands on the banner
     * and {@code facing} where her pose turns (toward the deals); {@code focal} the point between her eyes; {@code faceBox}
     * is never cropped or covered; {@code safeBox} (head to hands) must stay in view; {@code cropFloor} the lowest point
     * the picture may be cut at (a fraction of its height: below it the picture may run off the banner's edge);
     * {@code anchor} BOTTOM (she stands on the banner's bottom edge). {@code measured}: false when the boxes are the
     * framing's defaults because the measuring failed.
     */
    public record Layout(String figureSide, String facing, String framing, Point focal, Box faceBox, Box safeBox,
                         double cropFloor, String anchor, boolean measured) {}

    public record PhotoView(UUID id, String imageUrl, String note) {}

    public record PoseView(UUID id, String mood, String framing, String side, String outfit, String backdropHex,
                           String edgeHex, String status, String imageUrl, String cutoutUrl, Integer width, Integer height, Layout layout,
                           String brief, String note, String model, String createdBy, String approvedBy, java.time.Instant createdAt) {}

    /** The library: the reference photos and every pose, newest first. */
    public record Library(List<PhotoView> photos, List<PoseView> poses) {}

    /** {@code count} 1 to 4; {@code note} an optional extra for Gemini ("holding a gel polish bottle"). */
    public record MakeRequest(String mood, String framing, String side, String outfit, String backdropHex,
                              Integer count, String note, String expression, String camera, String light, String holding,
                              Map<String, String> custom) {}

    public record AddPhoto(String imageUrl, String note) {}

    // ---------------------------------------------------------------- the library

    public Library library() {
        return tx.execute(s -> new Library(
                photos.findAllByOrderByCreatedAtAsc().stream().map(p -> new PhotoView(p.getId(), p.getImageUrl(), p.getNote())).toList(),
                poses.findAllByOrderByCreatedAtDesc().stream().map(OwnerPoseService::view).toList()));
    }

    public Library addPhoto(AddPhoto in) {
        if (in == null || in.imageUrl() == null || !in.imageUrl().startsWith("https://")) {
            throw new UnprocessableException("Upload a photo first.");
        }
        tx.executeWithoutResult(s -> {
            OwnerPhoto p = new OwnerPhoto();
            p.setImageUrl(in.imageUrl().strip());
            p.setNote(in.note() == null || in.note().isBlank() ? null : in.note().strip());
            photos.save(p);
        });
        return library();
    }

    /** Takes a photo out of the references; the picture itself is kept where it is stored. */
    public Library removePhoto(UUID id) {
        tx.executeWithoutResult(s -> photos.deleteById(id));
        return library();
    }

    private static String own(Map<String, String> custom, String key) {
        String v = custom == null ? null : custom.get(key);
        if (v == null || v.isBlank()) return null;
        String tidy = v.strip();
        return tidy.length() > MAX_WORDS ? tidy.substring(0, MAX_WORDS) : tidy;
    }

    /** A choice from the form, in words: the shop's own words win; a preset the form sends is already words. */
    private static OwnerPosePrompts.Extras extras(MakeRequest in) {
        Map<String, String> c = in.custom();
        return new OwnerPosePrompts.Extras(in.note(), own(c, "mood"), first(own(c, "expression"), in.expression()),
                first(own(c, "camera"), in.camera()), first(own(c, "light"), in.light()),
                first(own(c, "holding"), in.holding()), own(c, "outfit"), own(c, "framing"), own(c, "side"));
    }

    private static String first(String mine, String preset) {
        if (mine != null) return mine;
        if (preset == null || preset.isBlank()) return null;
        String tidy = preset.strip();
        return tidy.length() > MAX_WORDS ? tidy.substring(0, MAX_WORDS) : tidy;
    }

    /** What staff asked for beyond the choices, kept with the pose so its card can say so. */
    private static String summary(MakeRequest in) {
        OwnerPosePrompts.Extras x = extras(in);
        List<String> parts = new ArrayList<>();
        if (in.note() != null && !in.note().isBlank()) parts.add(in.note().strip());
        for (String v : java.util.Arrays.asList(x.pose(), x.expression(), x.holding(), x.outfit(), x.framing(), x.camera(), x.light(), x.side())) {
            if (v != null) parts.add(v);
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /** Starts {@code count} poses in the background; each shows as "making" until it is ready or failed. */
    public Library make(MakeRequest in, String staff) {
        if (!config.geminiConfigured() || !cloudinary.configured()) {
            throw new UnprocessableException("Gemini or photo storage is not set up, so poses can't be made.");
        }
        List<UUID> made = tx.execute(s -> {
            if (photos.count() == 0) throw new UnprocessableException("Add at least one real photo of the owner first.");
            int count = in.count() == null ? 1 : Math.max(1, Math.min(MAX_PER_REQUEST, in.count()));
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                // count is at most the number of directions, so no two pictures of a request are asked for alike
                OwnerPose p = new OwnerPose();
                p.setMood(enumOf(OwnerPose.Mood.class, in.mood(), OwnerPose.Mood.LOOK));
                p.setFraming(enumOf(OwnerPose.Framing.class, in.framing(), OwnerPose.Framing.KNEE));
                p.setSide(enumOf(OwnerPose.Side.class, in.side(), OwnerPose.Side.LEFT));
                p.setOutfit(enumOf(OwnerPose.Outfit.class, in.outfit(), OwnerPose.Outfit.PINK_BLOUSE));
                // Always the green screen now: she is cut out of it, and the banner chooses its own colour.
                p.setBackdropHex(PoseCutout.SCREEN_HEX);
                p.setStatus(OwnerPose.Status.MAKING);
                p.setBrief(summary(in));
                p.setModel(config.getPoseModel());
                p.setCreatedBy(staff);
                ids.add(poses.save(p).getId());
            }
            return ids;
        });
        int first = ThreadLocalRandom.current().nextInt(OwnerPosePrompts.VARIANTS);
        OwnerPosePrompts.Extras extras = extras(in);
        for (int i = 0; i < made.size(); i++) self.makeInBackground(made.get(i), first + i, extras);
        return library();
    }

    @Async
    public void makeInBackground(UUID poseId, int variant, OwnerPosePrompts.Extras extras) {
        makeNow(poseId, variant, extras);
    }

    /** The work itself (tests call it directly). */
    void makeNow(UUID poseId, int variant, OwnerPosePrompts.Extras extras) {
        record Job(OwnerPose pose, List<String> refs) {}
        Job job = tx.execute(s -> {
            OwnerPose p = poses.findById(poseId).orElse(null);
            if (p == null || p.getStatus() != OwnerPose.Status.MAKING) return null;
            List<String> refs = photos.findAllByOrderByCreatedAtAsc().stream().map(OwnerPhoto::getImageUrl)
                    .limit(MAX_REFERENCES).toList();
            return new Job(p, refs);
        });
        if (job == null) return;
        try {
            List<Image> refs = new ArrayList<>();
            for (String url : job.refs()) refs.add(images.download(url));
            if (refs.isEmpty()) throw new UnprocessableException("There is no photo of the owner to start from.");
            OwnerPose p = job.pose();
            String prompt = OwnerPosePrompts.prompt(p, extras.note(), variant, extras);
            Image chosen = paint(p, refs, prompt);
            Optional<byte[]> alone = PoseCutout.cut(chosen.bytes());
            if (alone.isEmpty()) {
                // Shelves from the reference photos, or an uneven screen: one more try, then say so.
                chosen = paint(p, refs, prompt + OwnerPosePrompts.backdropAgain(p.getBackdropHex()));
                alone = PoseCutout.cut(chosen.bytes());
            }
            if (alone.isEmpty()) {
                fail(poseId, "Her green screen didn't come out clean enough to cut her out. Try again.");
                return;
            }
            Measured m = measure(chosen);
            Layout layout = layout(p, chosen);
            String url = cloudinary.upload(chosen.bytes(), "owner-poses",
                    "pose-" + p.getMood().name().toLowerCase(Locale.ROOT) + "-" + clock.millis()).url();
            String cutout = cloudinary.upload(alone.get(), "owner-poses",
                    "cutout-" + p.getMood().name().toLowerCase(Locale.ROOT) + "-" + clock.millis()).url();
            tx.executeWithoutResult(s -> poses.findById(poseId).ifPresent(x -> {
                x.setStatus(OwnerPose.Status.READY);
                x.setImageUrl(url);
                x.setCutoutUrl(cutout);
                x.setWidth(m.width());
                x.setHeight(m.height());
                x.setEdgeHex(null); // the banner has its own colour, not her backdrop's
                x.setLayout(write(layout));
                x.setNote(layout.measured() ? null : "Her face and hands couldn't be measured; the usual places are assumed.");
            }));
        } catch (GeminiException e) {
            fail(poseId, e.isQuotaExceeded() ? "Gemini's limit for now is used up. Try again later." : e.getMessage());
        } catch (RuntimeException e) {
            fail(poseId, "The pose could not be made: " + e.getMessage());
        }
    }

    private Image paint(OwnerPose p, List<Image> refs, String prompt) {
        return images.edit(config.getPoseModel(), refs.get(0), refs.subList(1, refs.size()), prompt,
                OwnerPosePrompts.aspectRatio(p.getFraming()), OwnerPosePrompts.IMAGE_SIZE);
    }

    private void fail(UUID poseId, String why) {
        log.warn("Owner pose {} failed: {}", poseId, why);
        tx.executeWithoutResult(s -> poses.findById(poseId).ifPresent(x -> {
            x.setStatus(OwnerPose.Status.FAILED);
            x.setNote(why);
        }));
    }

    /** A person has looked: it is her, and fit for the shop. */
    public PoseView approve(UUID id, String staff) {
        return tx.execute(s -> {
            OwnerPose p = find(id);
            if (p.getStatus() != OwnerPose.Status.READY && p.getStatus() != OwnerPose.Status.REJECTED) {
                throw new UnprocessableException("Only a finished pose can be approved.");
            }
            p.setStatus(OwnerPose.Status.APPROVED);
            p.setApprovedBy(staff);
            p.setApprovedAt(clock.instant());
            return view(p);
        });
    }

    /** Not her, or not right: kept out of the banners (the picture is kept, never deleted). */
    public PoseView reject(UUID id) {
        return tx.execute(s -> {
            OwnerPose p = find(id);
            p.setStatus(OwnerPose.Status.REJECTED);
            return view(p);
        });
    }

    /** Staff correct where her face, hands or eyes are, or how low she may be cut. */
    public PoseView setLayout(UUID id, Layout in) {
        return tx.execute(s -> {
            OwnerPose p = find(id);
            Layout now = read(p.getLayout());
            if (now == null) throw new UnprocessableException("This pose has no layout yet.");
            Layout next = new Layout(now.figureSide(), now.facing(), now.framing(),
                    in.focal() == null ? now.focal() : clamp(in.focal()),
                    in.faceBox() == null ? now.faceBox() : clamp(in.faceBox()),
                    in.safeBox() == null ? now.safeBox() : clamp(in.safeBox()),
                    in.cropFloor() <= 0 ? now.cropFloor() : Math.min(1, in.cropFloor()), now.anchor(), true);
            p.setLayout(write(next));
            return view(p);
        });
    }

    /** An approved pose for a banner, or a refusal in staff's words. */
    OwnerPose approved(UUID id) {
        OwnerPose p = poses.findById(id).orElseThrow(() -> new NotFoundException("No such pose."));
        if (p.getStatus() != OwnerPose.Status.APPROVED) {
            throw new UnprocessableException("Approve this pose before a banner uses it.");
        }
        return p;
    }

    OwnerPose findOrNull(UUID id) {
        return id == null ? null : poses.findById(id).orElse(null);
    }

    // ---------------------------------------------------------------- measuring

    record Measured(int width, int height, String edgeHex) {}

    /** The picture's size and the colour of its backdrop, read from a thin band all round its edge. */
    static Measured measure(Image picture) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(picture.bytes()));
            if (img == null) return new Measured(0, 0, null);
            int w = img.getWidth(), h = img.getHeight();
            int band = Math.max(2, Math.min(w, h) / 40);
            long r = 0, g = 0, b = 0, n = 0;
            int step = Math.max(1, Math.min(w, h) / 200);
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    // the top band and both sides: the bottom may be her body, cut off by the frame
                    boolean edge = y < band || x < band || x >= w - band;
                    if (!edge) continue;
                    int rgb = img.getRGB(x, y);
                    r += (rgb >> 16) & 0xff;
                    g += (rgb >> 8) & 0xff;
                    b += rgb & 0xff;
                    n++;
                }
            }
            String hex = n == 0 ? null : String.format(Locale.ROOT, "#%02X%02X%02X", r / n, g / n, b / n);
            return new Measured(w, h, hex);
        } catch (IOException e) {
            return new Measured(0, 0, null);
        }
    }

    /** Where her face, hands and eyes are: asked of Gemini, else the framing's usual places. */
    private Layout layout(OwnerPose p, Image picture) {
        Layout fallback = defaults(p);
        if (text == null) return fallback;
        try {
            Map<String, Object> num = Map.of("type", "NUMBER");
            Map<String, Object> box = Map.of("type", "OBJECT",
                    "properties", Map.of("x", num, "y", num, "w", num, "h", num), "required", List.of("x", "y", "w", "h"));
            Map<String, Object> point = Map.of("type", "OBJECT",
                    "properties", Map.of("x", num, "y", num), "required", List.of("x", "y"));
            Map<String, Object> schema = Map.of("type", "OBJECT",
                    "properties", Map.of("faceBox", box, "safeBox", box, "focal", point),
                    "required", List.of("faceBox", "safeBox", "focal"));
            JsonNode a = text.ask(picture, OwnerPosePrompts.MEASURE, schema);
            Layout measured = measured(a, fallback);
            if (!measured.measured()) log.warn("Owner pose {}: measuring gave an unusable answer: {}", p.getId(), a);
            return measured;
        } catch (RuntimeException e) {
            log.warn("Could not measure owner pose {}: {}", p.getId(), e.getMessage());
            return fallback;
        }
    }

    /**
     * The layout from Gemini's answer, or {@code fallback} if it makes no sense. Gemini may answer boxes in thousandths
     * whatever it is asked, so those are scaled back. The safe box always holds the face.
     */
    static Layout measured(JsonNode a, Layout fallback) {
        double scale = 1;
        for (String part : List.of("faceBox", "safeBox", "focal")) {
            for (String field : List.of("x", "y", "w", "h")) {
                if (a.path(part).path(field).asDouble() > 1) scale = 1000;
            }
        }
        Box face = clamp(box(a.path("faceBox"), scale));
        Box safe = clamp(box(a.path("safeBox"), scale));
        Point focal = clamp(new Point(a.path("focal").path("x").asDouble() / scale, a.path("focal").path("y").asDouble() / scale));
        if (face.w() < 0.03 || face.h() < 0.03) return fallback;
        double left = Math.min(safe.x(), face.x()), top = Math.min(safe.y(), face.y());
        double right = Math.max(safe.x() + safe.w(), face.x() + face.w());
        double bottom = Math.max(safe.y() + safe.h(), face.y() + face.h());
        Box holding = new Box(left, top, right - left, bottom - top);
        double floor = Math.max(fallback.cropFloor(), Math.min(1, bottom));
        return new Layout(fallback.figureSide(), fallback.facing(), fallback.framing(), focal, face, holding, floor,
                "BOTTOM", true);
    }

    private static Box box(JsonNode n, double scale) {
        return new Box(n.path("x").asDouble() / scale, n.path("y").asDouble() / scale, n.path("w").asDouble() / scale,
                n.path("h").asDouble() / scale);
    }

    /** The usual places of her face and hands for each framing, before (or without) measuring. */
    static Layout defaults(OwnerPose p) {
        String side = p.getSide().name();
        String facing = p.getSide() == OwnerPose.Side.LEFT ? "RIGHT" : "LEFT";
        return switch (p.getFraming()) {
            case CLOSE -> new Layout(side, facing, "CLOSE", new Point(0.5, 0.42), new Box(0.2, 0.15, 0.6, 0.65),
                    new Box(0.05, 0.05, 0.9, 0.9), 1, "BOTTOM", false);
            case THIGH -> new Layout(side, facing, "THIGH", new Point(0.5, 0.19), new Box(0.35, 0.07, 0.28, 0.2),
                    new Box(0.04, 0.02, 0.92, 0.65), 0.68, "BOTTOM", false);
            case HEAD -> new Layout(side, facing, "HEAD", new Point(0.5, 0.38), new Box(0.28, 0.12, 0.44, 0.5),
                    new Box(0.08, 0.04, 0.84, 0.9), 0.95, "BOTTOM", false);
            case WAIST -> new Layout(side, facing, "WAIST", new Point(0.5, 0.22), new Box(0.34, 0.08, 0.3, 0.24),
                    new Box(0.04, 0.02, 0.92, 0.7), 0.72, "BOTTOM", false);
            case KNEE -> new Layout(side, facing, "KNEE", new Point(0.5, 0.17), new Box(0.36, 0.06, 0.26, 0.18),
                    new Box(0.04, 0.02, 0.92, 0.6), 0.62, "BOTTOM", false);
            case FULL -> new Layout(side, facing, "FULL", new Point(0.5, 0.11), new Box(0.4, 0.04, 0.2, 0.12),
                    new Box(0.06, 0.02, 0.88, 0.5), 0.5, "BOTTOM", false);
        };
    }

    // ---------------------------------------------------------------- helpers

    /** A pose as the public shop gets it: only what draws her, not staff names, their prompts or the green original. */
    static PoseView forShop(OwnerPose p) {
        return new PoseView(p.getId(), p.getMood().name(), p.getFraming().name(), p.getSide().name(), p.getOutfit().name(),
                p.getBackdropHex(), p.getEdgeHex(), p.getStatus().name(),
                p.getCutoutUrl() != null ? p.getCutoutUrl() : p.getImageUrl(), p.getCutoutUrl(), p.getWidth(), p.getHeight(),
                read(p.getLayout()), null, null, null, null, null, p.getCreatedAt());
    }

    static PoseView view(OwnerPose p) {
        return new PoseView(p.getId(), p.getMood().name(), p.getFraming().name(), p.getSide().name(),
                p.getOutfit().name(), p.getBackdropHex(), p.getEdgeHex(), p.getStatus().name(), p.getImageUrl(), p.getCutoutUrl(),
                p.getWidth(), p.getHeight(), read(p.getLayout()), p.getBrief(), p.getNote(), p.getModel(), p.getCreatedBy(),
                p.getApprovedBy(), p.getCreatedAt());
    }

    private OwnerPose find(UUID id) {
        return poses.findById(id).orElseThrow(() -> new NotFoundException("No such pose."));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, E otherwise) {
        if (raw == null || raw.isBlank()) return otherwise;
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException("\"" + raw + "\" isn't one of the choices.");
        }
    }

    private static double unit(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    private static Box clamp(Box b) {
        double x = unit(b.x()), y = unit(b.y());
        return new Box(x, y, Math.min(unit(b.w()), 1 - x), Math.min(unit(b.h()), 1 - y));
    }

    private static Point clamp(Point p) {
        return new Point(unit(p.x()), unit(p.y()));
    }

    static String write(Layout layout) {
        try {
            return JSON.writeValueAsString(layout);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Layout read(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, Layout.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
