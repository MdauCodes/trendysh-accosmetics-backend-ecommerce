package com.nbh.backend.banners;

import java.util.List;

/**
 * What Gemini is asked for one of the owner's poses. Her likeness comes from the real photos, but everything else must be
 * new: on 4 Nov 2026 the owner rejected near-identical stiff pictures from one repeated prompt ("variants must be
 * variants"). So each mood has four directions, each with its own body, expression, camera and light, and every picture of
 * a request takes a different one. The shop's own note comes first and replaces the mood's pose rather than competing with
 * it; the variants then differ in how they play it. Her feet stay out unless the framing is FULL (owner: "avoid including
 * them unless we have to").
 */
final class OwnerPosePrompts {

    private OwnerPosePrompts() {}

    /** How many different pictures one mood can give; a request never exceeds this. */
    static final int VARIANTS = 4;

    static final String IDENTITY = "The attached photos show one real woman, the owner of a beauty-supply shop in Nairobi."
            + " Make a NEW professional advertising photograph of THIS SAME woman. Keep who she is exactly: her face shape,"
            + " cheekbones and jaw, her dark brown skin tone with real skin texture (never lightened or plastic), her eyes and"
            + " eyebrows, her thin long locs pulled back from her forehead, berry lipstick and clean, even make-up, small gold"
            + " hoop earrings and rose-gold watch. She must be instantly recognisable. But her pose, expression, camera angle"
            + " and lighting are completely NEW: do not copy them from the photos. Take nothing from the place behind her in"
            + " the photos: no shelves, products, boxes or furniture. ";

    /** One way of playing a mood. {T} is the side of the frame the deals are on, {H} her hand on that side. */
    record Direction(String body, String face, String camera, String light) {}

    private static final List<Direction> LOOK = List.of(
            new Direction("she presents something to the {T} of the frame with her {H} hand raised, palm open and up, at shoulder height",
                    "her head and eyes turn to that open hand, with a warm, delighted smile, as if saying 'look at these deals!'",
                    "straight on at eye level", "bright, soft beauty light"),
            new Direction("her whole upper body leans toward the {T} of the frame, both arms sweeping out that way like a game-show host revealing a prize",
                    "a big open-mouthed smile, eyes on the camera", "slightly from below", "crisp, high-energy light"),
            new Direction("one hand on her hip, the other gesturing easily toward the {T} of the frame, her head tilted",
                    "a sly, knowing smile, one eyebrow raised, a side glance toward the {T}", "three-quarter view from slightly above",
                    "side light with gentle shadows on her face"),
            new Direction("she has just spun toward the {T} of the frame, one shoulder forward, locs swinging with the movement, one finger lifted as if she has spotted something",
                    "eyes wide, a delighted 'oh!'", "close, at eye level", "warm glow with a rim light on her hair"));

    private static final List<Direction> AMAZED = List.of(
            new Direction("both hands raised open beside her shoulders, leaning toward the {T} of the frame",
                    "eyes wide open, eyebrows up, mouth open in a joyful 'wow!', looking toward the {T}", "straight on at eye level",
                    "bright, punchy light"),
            new Direction("one hand flat on her chest, the other flung out toward the {T} of the frame, her body leaning back in disbelief",
                    "jaw dropped mid-'WHAT?!', eyebrows shooting up, eyes fixed on the {T}", "slightly from below", "crisp, clean light"),
            new Direction("both hands gripping the sides of her head, elbows out, frozen on the spot",
                    "mouth wide open, stunned, eyes as wide as they go, staring toward the {T}", "close, at eye level",
                    "dramatic light with defined shadows"),
            new Direction("hands clasped together under her chin, shoulders lifted with excitement, bouncing on her toes",
                    "squealing with delight, eyes shining, looking toward the {T}", "slightly from above", "soft, warm, glowing light"));

    private static final List<Direction> SURPRISED = List.of(
            new Direction("both hands lightly on her cheeks", "a huge gasp, eyes very wide, eyebrows high, looking toward the {T}",
                    "close and straight on", "bright beauty light"),
            new Direction("fingertips covering her mouth, shoulders drawn up", "eyes wide above her fingers, peeking toward the {T}, trying not to laugh",
                    "slightly from above", "soft window-like light"),
            new Direction("head pulled back, chin tucked, one hand raised palm out as if saying 'wait, really?'",
                    "one eyebrow very high, lips parted, looking toward the {T}", "three-quarter view at eye level", "crisp side light"),
            new Direction("leaning forward at the waist toward the {T} of the frame, one hand on her collarbone",
                    "eyes narrowing then widening in disbelief, mouth forming an 'oh!'", "slightly from below", "warm light with a rim on her shoulder"));

    private static final List<Direction> MISS_OUT = List.of(
            new Direction("a playful shrug, both palms up", "eyebrows raised, a knowing side glance toward the {T}, lips pressed in a cheeky half smile",
                    "straight on at eye level", "clean, bright light"),
            new Direction("arms folded, head tilted, weight on one hip, a slow shake of the head",
                    "pursed lips and one raised eyebrow: 'don't say I didn't tell you', eyes on the camera", "slightly from below, so she looks powerful",
                    "dramatic side light"),
            new Direction("she lifts her wrist toward the camera and taps her rose-gold watch with one finger",
                    "eyebrows high, a teasing smile: time is running out", "close, at eye level", "crisp, glossy light"),
            new Direction("leaning in close with one hand cupped beside her mouth, whispering a secret",
                    "eyes sliding playfully toward the {T}, a mischievous grin", "close, from slightly above", "moody light with a rim on her hair"));

    private static final List<Direction> WELCOME = List.of(
            new Direction("one arm opening toward the {T} of the frame, inviting you in to the shop", "a big friendly smile straight at the camera",
                    "straight on at eye level", "warm, bright light"),
            new Direction("both arms thrown wide open", "laughing with joy, eyes on the camera", "slightly from below", "fresh, high-energy light"),
            new Direction("one hand resting on her chest, a slight bow of the head", "a gentle, sincere smile straight at the camera",
                    "close, three-quarter view", "soft window-like light"),
            new Direction("waving hello with one hand high, her head tipped back a little", "caught mid-laugh, eyes creased", "slightly off-centre at eye level",
                    "warm light with a rim on her hair"));

    private static final List<Direction> POINTING = List.of(
            new Direction("she points with her {H} index finger toward the {T} of the frame", "looking there too, excited, as if saying 'right here!'",
                    "straight on at eye level", "bright, crisp light"),
            new Direction("mid-bounce with energy, both index fingers pointing firmly to the {T}, shoulders twisted that way, locs swinging",
                    "a huge open-mouthed laugh straight into the camera", "slightly from below", "high-energy light"),
            new Direction("her arm extended with one finger pointing down and toward the {T}, as if at a price tag",
                    "eyebrows raised, mouth in a 'see?', eyes on the camera", "three-quarter view from slightly above", "side light with gentle shadows"),
            new Direction("one hand on her waist, the other making a playful finger-gun toward the {T}",
                    "a wink and a confident grin at the camera", "close, at eye level", "glossy, warm light"));

    /** When the shop has written the pose itself: the same words, played four different ways. */
    private static final List<String> TAKES = List.of(
            "Play it big and theatrical, full of comedy.",
            "Play it with a slow, knowing confidence.",
            "Catch it mid-movement, candid and alive, with her locs moving.",
            "Play it up close and personal, leaning toward the camera.");

    static List<Direction> directions(OwnerPose.Mood mood) {
        return switch (mood) {
            case LOOK -> LOOK;
            case AMAZED -> AMAZED;
            case SURPRISED -> SURPRISED;
            case MISS_OUT -> MISS_OUT;
            case WELCOME -> WELCOME;
            case POINTING -> POINTING;
        };
    }

    static String outfit(OwnerPose.Outfit outfit) {
        return switch (outfit) {
            case PINK_BLOUSE -> "She wears her own hot-pink blouse with ruffled puff sleeves and her black pencil skirt. ";
            case BLAZER -> "She wears her own hot-pink collared shirt under a black blazer, with a black pencil skirt. ";
        };
    }

    private static Direction direction(OwnerPose.Mood mood, int variant) {
        List<Direction> all = directions(mood);
        return all.get(Math.floorMod(variant, all.size()));
    }

    private static String sided(String text, OwnerPose.Side side) {
        return text.replace("{T}", side == OwnerPose.Side.LEFT ? "RIGHT" : "LEFT")
                .replace("{H}", side == OwnerPose.Side.LEFT ? "right" : "left");
    }

    /** The pose, turned toward the deals: they are on the side of the picture away from where she stands. */
    static String pose(OwnerPose.Mood mood, OwnerPose.Side side, int variant) {
        Direction d = direction(mood, variant);
        return sided("Pose: " + d.body() + ". Expression: " + d.face() + ". ", side);
    }

    static String camera(OwnerPose.Mood mood, int variant) {
        Direction d = direction(mood, variant);
        return "Camera: " + d.camera() + ". Lighting: " + d.light() + ". ";
    }

    static String framing(OwnerPose.Framing framing) {
        return switch (framing) {
            case HEAD -> "Framing: head and shoulders, from just above her head to the middle of her chest. ";
            case WAIST -> "Framing: waist up, from just above her head to her waist. ";
            case KNEE -> "Framing: cropped just above the knees, from just above her head to above her knees; her feet and"
                    + " shoes are NOT in the picture. ";
            case FULL -> "Framing: full height, from the top of her head to her shoes (black flats with a gold ankle strap),"
                    + " small empty margin around her. ";
        };
    }

    static String backdrop(String hex) {
        return "Background: a seamless, perfectly flat studio colour, exactly " + hex + ", the same from edge to edge, with no"
                + " gradient, vignette, texture, floor line or shadow on it. ";
    }

    /** Said again on the second try, when the first came back on the wrong backdrop. */
    static String backdropAgain(String hex) {
        return " IMPORTANT: the last attempt got the background wrong. Every pixel around her must be exactly " + hex
                + ", with nothing else behind her at all.";
    }

    static final String QUALITY = "High-end beauty-brand campaign photo, shot on a full-frame camera with an 85mm lens, crisp"
            + " detail in her eyes and skin, full of life and personality, nothing stiff or stock-looking. Natural hands with"
            + " five fingers each, natural proportions. One person only. No text, no letters, no logos, no watermark, no frame.";

    /** The picture's shape: tall for a standing figure, nearly square for head and shoulders. */
    static String aspectRatio(OwnerPose.Framing framing) {
        return switch (framing) {
            case HEAD -> "4:5";
            case WAIST, KNEE -> "3:4";
            case FULL -> "9:16";
        };
    }

    /** The size asked of the model: banners, and later the shop-window screen, need more than the default 1K. */
    static final String IMAGE_SIZE = "2K";

    /** {@code variant} picks the direction: different numbers give different pictures. */
    static String prompt(OwnerPose p, String note, int variant) {
        boolean own = note != null && !note.isBlank();
        String pose = own
                ? "The shop's own direction for this picture comes first; follow it over anything else here: " + note.strip()
                        + ". " + TAKES.get(Math.floorMod(variant, TAKES.size())) + " "
                : pose(p.getMood(), p.getSide(), variant);
        return IDENTITY + outfit(p.getOutfit()) + pose + framing(p.getFraming()) + camera(p.getMood(), variant)
                + backdrop(p.getBackdropHex()) + QUALITY;
    }

    /** Asked of Gemini's text model about the finished picture: where her face, eyes and pose are, for the layout. */
    static final String MEASURE = "This is a photo of one woman on a plain backdrop. Give boxes as fractions of the image"
            + " width and height (0 to 1, from the top-left corner): faceBox = her face from hairline to chin and ear to"
            + " ear; safeBox = the smallest box holding her head and both hands (what must never be cut off); focal = the"
            + " point between her eyes.";
}
