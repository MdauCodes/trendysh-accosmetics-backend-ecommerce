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

    private static final List<Direction> BOSS = List.of(
            new Direction("arms folded, chin lifted, weight on one hip, the woman who owns the place", "a slow, confident smile straight into the camera",
                    "from a low angle, so she looks powerful", "dramatic side light"),
            new Direction("both hands on her hips, shoulders back, standing tall", "a proud, wide smile at the camera", "straight on at eye level", "crisp, clean light"),
            new Direction("one hand adjusting her collar, the other at her side, stepping forward", "one eyebrow raised, a knowing half smile", "three-quarter view from slightly below",
                    "glossy light with a rim on her shoulder"),
            new Direction("leaning slightly forward with her hands clasped in front of her, as if about to make you an offer", "steady eyes on the camera, a warm, assured smile",
                    "close, at eye level", "soft, even light"));

    private static final List<Direction> LAUGHING = List.of(
            new Direction("head tipped back, one hand on her chest, shoulders loose", "full, real laughter, eyes nearly shut", "slightly from below", "warm, window-like light"),
            new Direction("bent a little at the waist, one hand on her stomach, the other waving as if to say 'stop it!'", "laughing with her mouth wide open", "straight on at eye level", "bright, fresh light"),
            new Direction("a hand lightly covering her mouth, shoulders lifted", "giggling, eyes creased and sparkling", "close, from slightly above", "soft beauty light"),
            new Direction("turning toward the {T} of the frame mid-laugh, locs swinging", "a big laugh shared with someone there", "three-quarter view at eye level", "warm light with a rim on her hair"));

    private static final List<Direction> SECRET = List.of(
            new Direction("leaning in with one hand cupped beside her mouth, whispering", "eyes sliding toward the {T}, a mischievous grin", "close, from slightly above", "moody light with a rim on her hair"),
            new Direction("one finger on her lips, saying 'shh', leaning toward the camera", "playful wide eyes, one eyebrow raised", "close, at eye level", "soft, glossy light"),
            new Direction("glancing over her shoulder as if checking nobody is listening, one hand shielding her mouth", "a cheeky, conspiratorial look", "three-quarter view at eye level", "side light with gentle shadows"),
            new Direction("one hand pointing discreetly toward the {T}, kept low by her hip", "a sly sideways look at the camera, lips pressed in a smile", "slightly from above", "warm, soft light"));

    private static final List<Direction> BECKON = List.of(
            new Direction("one hand held out, curling her fingers to call you over", "an inviting smile straight at the camera", "straight on at eye level", "warm, bright light"),
            new Direction("both hands waving you in toward the {T} of the frame", "excited, mouth open as if calling 'come, come!'", "slightly from below", "high-energy light"),
            new Direction("one arm stretched out toward the camera, palm up, as if offering you her hand", "a warm, encouraging smile", "close, with her hand large and slightly soft in front", "soft beauty light"),
            new Direction("half turned toward the {T}, looking back over her shoulder, one hand beckoning", "a playful, knowing smile at the camera", "three-quarter view at eye level", "warm light with a rim on her hair"));

    private static final List<Direction> THUMBS_UP = List.of(
            new Direction("one big thumbs up held toward the camera", "a wide, sure smile", "straight on at eye level", "bright, crisp light"),
            new Direction("both thumbs up at chest height, shoulders bouncing", "beaming, eyes creased", "slightly from below", "high-energy light"),
            new Direction("an 'OK' sign with one hand beside her cheek, the other on her hip", "a wink and a grin", "close, at eye level", "glossy light"),
            new Direction("nodding, one thumb pointing toward the {T} of the frame", "an approving 'this one!' look, lips pushed out", "three-quarter view from slightly above", "side light with gentle shadows"));

    private static final List<Direction> CELEBRATE = List.of(
            new Direction("both fists raised high in triumph", "shouting with joy, mouth wide open", "slightly from below", "high-energy light"),
            new Direction("mid-dance, one arm up and hips swaying, locs swinging", "a huge joyful smile, eyes on the camera", "straight on at eye level", "bright, punchy light"),
            new Direction("clapping her hands in front of her chest, shoulders lifted", "delighted, laughing", "close, at eye level", "warm, glowing light"),
            new Direction("arms flung wide open and head tipped back", "pure happiness, eyes closed, big smile", "from below", "fresh, bright light"));

    private static final List<Direction> THINKING = List.of(
            new Direction("one finger tapping her chin, other arm across her waist", "looking up and toward the {T}, lips pursed: 'hmm, which one?'", "straight on at eye level", "soft, even light"),
            new Direction("hand under her chin, head tilted", "narrowed eyes and a small smile: she has just had an idea", "close, three-quarter view", "side light with gentle shadows"),
            new Direction("one finger raised beside her head, the moment of an idea", "eyes wide and bright, mouth open in an 'aha!'", "slightly from below", "crisp, glossy light"),
            new Direction("arms folded with one hand at her cheek, weighing it up", "one eyebrow raised, a sideways look toward the {T}", "three-quarter view at eye level", "warm, soft light"));

    private static final List<Direction> SHOPPING = List.of(
            new Direction("striding toward the {T} of the frame mid-step, carrying plain unbranded pink and white shopping bags in one raised hand", "looking back at the camera with a delighted smile", "three-quarter view at eye level", "bright, fresh light"),
            new Direction("holding up plain unbranded shopping bags in both hands, arms wide", "triumphant, laughing", "slightly from below", "high-energy light"),
            new Direction("hugging a plain unbranded shopping bag to her chest with both arms", "eyes closed, a blissful smile", "close, at eye level", "warm, soft light"),
            new Direction("peeking into a plain unbranded shopping bag held open in front of her", "eyes wide with delight, mouth in an 'ooh'", "from slightly above", "glossy light"));

    /** Chosen by the shop on top of the mood: each replaces what the direction would have done; empty leaves it free. */
    record Extras(String note, String pose, String expression, String camera, String light, String holding, String outfit,
                  String framing, String side) {
        static final Extras NONE = new Extras(null, null, null, null, null, null, null, null, null);
    }

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
            case BOSS -> BOSS;
            case LAUGHING -> LAUGHING;
            case SECRET -> SECRET;
            case BECKON -> BECKON;
            case THUMBS_UP -> THUMBS_UP;
            case CELEBRATE -> CELEBRATE;
            case THINKING -> THINKING;
            case SHOPPING -> SHOPPING;
            case OWN -> LOOK; // only its camera and light are used: the shop's words are the pose
        };
    }

    static String outfit(OwnerPose.Outfit outfit) {
        return switch (outfit) {
            case PINK_BLOUSE -> "She wears her own hot-pink blouse with ruffled puff sleeves and her black pencil skirt. ";
            case BLAZER -> "She wears her own hot-pink collared shirt under a black blazer, with a black pencil skirt. ";
            case WHITE_BLAZER -> "She wears a crisp white tailored blazer over a magenta top, with a black pencil skirt. ";
            case BLACK_DRESS -> "She wears an elegant fitted black dress with three-quarter sleeves. ";
            case MAGENTA_DRESS -> "She wears an elegant fitted magenta dress with three-quarter sleeves. ";
            case OWN -> "";
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
            case CLOSE -> "Framing: a close-up of her face and neck, filling the picture. ";
            case HEAD -> "Framing: head and shoulders, from just above her head to the middle of her chest. ";
            case WAIST -> "Framing: waist up, from just above her head to her waist. ";
            case THIGH -> "Framing: from just above her head to mid-thigh; her knees and feet are NOT in the picture. ";
            case KNEE -> "Framing: cropped just above the knees, from just above her head to above her knees; her feet and"
                    + " shoes are NOT in the picture. ";
            case FULL -> "Framing: full height, from the top of her head to her shoes (black flats with a gold ankle strap),"
                    + " small empty margin around her. ";
        };
    }

    static String backdrop(String hex) {
        if (PoseCutout.SCREEN_HEX.equals(hex)) {
            return "Background: a professional chroma-key green screen, exactly " + hex + ", evenly lit and the same from edge"
                    + " to edge, with no gradient, vignette, floor line or shadow on it. No green light falls on her; nothing"
                    + " she wears is green. Keep clean, crisp edges around her hair and hands so she can be cut out. ";
        }
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
            case CLOSE, HEAD -> "4:5";
            case WAIST, THIGH, KNEE -> "3:4";
            case FULL -> "9:16";
        };
    }

    /** The size asked of the model: banners, and later the shop-window screen, need more than the default 1K. */
    static final String IMAGE_SIZE = "2K";

    /** {@code variant} picks the direction: different numbers give different pictures. */
    static String prompt(OwnerPose p, String note, int variant) {
        return prompt(p, note, variant, Extras.NONE);
    }

    /** As above, with what the shop chose or wrote for each setting. Fixed settings still leave the takes to vary. */
    static String prompt(OwnerPose p, String note, int variant, Extras x) {
        Direction d = direction(p.getMood(), variant);
        boolean ownNote = filled(note);
        boolean ownPose = filled(x.pose()) || p.getMood() == OwnerPose.Mood.OWN;
        StringBuilder out = new StringBuilder(IDENTITY);
        out.append(filled(x.outfit()) ? "She wears " + x.outfit().strip() + ". " : outfit(p.getOutfit()));
        if (ownNote) {
            out.append("The shop's own direction for this picture comes first; follow it over anything else here: ")
                    .append(note.strip()).append(". ");
        }
        if (filled(x.pose())) out.append("Pose: ").append(x.pose().strip()).append(". ");
        else if (!ownNote && !ownPose) out.append(sided("Pose: " + d.body() + ". ", p.getSide()));
        if (filled(x.expression())) out.append("Expression: ").append(x.expression().strip()).append(". ");
        else if (!ownNote && !ownPose) out.append(sided("Expression: " + d.face() + ". ", p.getSide()));
        if (ownNote || ownPose || filled(x.expression())) {
            out.append(TAKES.get(Math.floorMod(variant, TAKES.size()))).append(' ');
        }
        if (filled(x.holding())) out.append("She is holding ").append(x.holding().strip()).append(", with no readable brand or label. ");
        if (filled(x.side())) out.append("Placing and direction: ").append(x.side().strip()).append(". ");
        out.append(filled(x.framing()) ? "Framing: " + x.framing().strip() + ". " : framing(p.getFraming()));
        out.append("Camera: ").append(filled(x.camera()) ? x.camera().strip() : d.camera()).append(". ");
        out.append("Lighting: ").append(filled(x.light()) ? x.light().strip() : d.light()).append(". ");
        return out + backdrop(p.getBackdropHex()) + QUALITY;
    }

    private static boolean filled(String s) {
        return s != null && !s.isBlank();
    }

    /** Asked of Gemini's text model about the finished picture: where her face, eyes and pose are, for the layout. */
    static final String MEASURE = "This is a photo of one woman on a plain backdrop. Give boxes as fractions of the image"
            + " width and height (0 to 1, from the top-left corner): faceBox = her face from hairline to chin and ear to"
            + " ear; safeBox = the smallest box holding her head and both hands (what must never be cut off); focal = the"
            + " point between her eyes.";
}
