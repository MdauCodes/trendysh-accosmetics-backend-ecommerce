package com.nbh.backend.banners;

/**
 * What Gemini is asked for one of the owner's poses (owner, 2026-11-04). Written from the 4 Nov trial, where these words
 * kept her likeness on Nano Banana 2 and Pro: say plainly it is the same real woman, list what makes her her (face, dark
 * skin not lightened, locs, berry lipstick, gold hoops, rose-gold watch), one pose, one framing, a flat backdrop. Her
 * feet are left out unless the framing is FULL (owner: "avoid including them unless we have to"). The trial's flaws are
 * answered here too: her eyes follow the hand that presents the deals, and the make-up is kept clean.
 */
final class OwnerPosePrompts {

    private OwnerPosePrompts() {}

    static final String IDENTITY = "The attached photos all show the same real woman, the owner of a beauty-supply shop in"
            + " Nairobi. Make a NEW photograph of THIS SAME woman. Keep her identity exactly as in the photos: the same face"
            + " shape, cheekbones and jaw, the same dark brown skin tone (do not lighten it or smooth it into plastic), the"
            + " same eyes and eyebrows, the same smile and teeth, the same thin long locs pulled back from her forehead, the"
            + " same berry lipstick and clean, even make-up (no smudges or stray colour on her face), the same small gold"
            + " hoop earrings and her rose-gold watch. She must be instantly recognisable as the same person. ";

    static String outfit(OwnerPose.Outfit outfit) {
        return switch (outfit) {
            case PINK_BLOUSE -> "She wears her own hot-pink blouse with ruffled puff sleeves and her black pencil skirt. ";
            case BLAZER -> "She wears her own hot-pink collared shirt under a black blazer, with a black pencil skirt. ";
        };
    }

    /** The pose, turned toward the deals: they are on the side of the picture away from where she stands. */
    static String pose(OwnerPose.Mood mood, OwnerPose.Side side) {
        String toward = side == OwnerPose.Side.LEFT ? "RIGHT" : "LEFT";
        String hand = side == OwnerPose.Side.LEFT ? "right" : "left";
        return switch (mood) {
            case LOOK -> "Pose: she presents something to the " + toward + " side of the frame with her " + hand + " hand"
                    + " raised, palm open and up, at shoulder height, and her head and eyes turn to look AT that open hand"
                    + " with a warm, delighted smile, as if saying 'look at these deals!'. ";
            case AMAZED -> "Pose: she is amazed and thrilled at something just to the " + toward + " of the frame: both"
                    + " hands raised open beside her shoulders, eyes wide open, eyebrows up, mouth open in a joyful 'wow!',"
                    + " her eyes on that side, leaning slightly toward it. ";
            case SURPRISED -> "Pose: genuine happy surprise, eyes very wide open and eyebrows high, both hands lightly on"
                    + " her cheeks, mouth open in an 'oh!', looking toward the " + toward + " of the frame. ";
            case MISS_OUT -> "Pose: a playful 'don't say I didn't tell you' shrug: both palms up, eyebrows raised, a knowing"
                    + " side glance toward the " + toward + " of the frame, lips pressed in a cheeky half smile. ";
            case WELCOME -> "Pose: a warm welcome: a big friendly smile straight at the camera, one arm opening toward the "
                    + toward + " side as if inviting you in to the shop. ";
            case POINTING -> "Pose: she points with her " + hand + " index finger toward the " + toward + " side of the"
                    + " frame, looking there too, excited, as if saying 'right here!'. ";
        };
    }

    static String framing(OwnerPose.Framing framing, OwnerPose.Side side) {
        String turn = side == OwnerPose.Side.LEFT ? "right" : "left";
        return switch (framing) {
            case HEAD -> "Framing: head and shoulders, from just above her head to the middle of her chest. ";
            case WAIST -> "Framing: waist up, standing, from just above her head to her waist, body turned three-quarters"
                    + " toward the " + turn + ". ";
            case KNEE -> "Framing: standing, cropped just above the knees, from just above her head to above her knees;"
                    + " her feet and shoes are NOT in the picture. Body turned three-quarters toward the " + turn + ". ";
            case FULL -> "Framing: full height, standing, from the top of her head to her shoes (black flats with a gold"
                    + " ankle strap), body turned three-quarters toward the " + turn + ", small empty margin around her. ";
        };
    }

    static String backdrop(String hex) {
        return "Background: a completely flat, plain, uniform colour " + hex + " from edge to edge, no texture, no"
                + " gradient, no shadow on the wall, no floor line. A soft rim light separates her from it.";
    }

    static final String QUALITY = " Photorealistic commercial portrait, soft even studio light, sharp focus on her face,"
            + " natural hands with five fingers each, natural proportions. One person only. No text, no letters, no logos,"
            + " no watermark, no frame.";

    /** The picture's shape: tall for a standing figure, nearly square for head and shoulders. */
    static String aspectRatio(OwnerPose.Framing framing) {
        return switch (framing) {
            case HEAD -> "4:5";
            case WAIST, KNEE -> "3:4";
            case FULL -> "9:16";
        };
    }

    static String prompt(OwnerPose p, String note) {
        String extra = note == null || note.isBlank() ? "" : " Also: " + note.strip() + ".";
        return IDENTITY + outfit(p.getOutfit()) + pose(p.getMood(), p.getSide()) + framing(p.getFraming(), p.getSide())
                + backdrop(p.getBackdropHex()) + QUALITY + extra;
    }

    /** Asked of Gemini's text model about the finished picture: where her face, eyes and pose are, for the layout. */
    static final String MEASURE = "This is a photo of one woman on a plain backdrop. Give boxes as fractions of the image"
            + " width and height (0 to 1, from the top-left corner): faceBox = her face from hairline to chin and ear to"
            + " ear; safeBox = the smallest box holding her head and both hands (what must never be cut off); focal = the"
            + " point between her eyes.";
}
