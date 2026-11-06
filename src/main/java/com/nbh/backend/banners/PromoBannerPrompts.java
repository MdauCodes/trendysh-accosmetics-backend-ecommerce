package com.nbh.backend.banners;

import java.util.List;
import java.util.Locale;

/**
 * What Gemini is asked for a builder banner's picture (owner, 2026-10-31; made "salesy" the same day after the first
 * pictures looked like lifestyle photos that sold nothing). The instruction stands on its own, modelled on the big
 * marketplaces' campaign tiles: the shop's real products as the hero on podiums against one soft colour, sales props,
 * studio light, and clear space where the shop draws the headline and button. What staff type is only a mood hint,
 * cleaned of anything that would fight the rules ({@link #moodHint}). The picture never has words of its own: the shop
 * draws them over it, sharp and changeable.
 */
final class PromoBannerPrompts {

    private PromoBannerPrompts() {
    }

    /** The picture's shape for Gemini, closest to how the placement is drawn. */
    static String aspectRatio(PromoBanner.Placement placement) {
        return switch (placement) {
            case HERO -> "21:9";
            case STRIP -> "21:9";
            case TILE -> "5:4";
            case OFFER_CARD, GRID_TILE -> "3:4";
            case CIRCLE -> "1:1";
        };
    }

    /** Where the products go and which parts stay clear, matching where the shop draws the words. */
    static String layout(PromoBanner.Placement placement) {
        return switch (placement) {
            case HERO, STRIP -> "A wide, short web banner. Arrange the products as a tight group in the RIGHT 45% of"
                    + " the frame, large and sharp. The LEFT 55% must be a clean, smooth continuation of the backdrop"
                    + " with nothing in it, because the headline, offer and button are drawn there.";
            case TILE -> "A square-ish promo tile. Put the products in the MIDDLE of the frame, filling about half of it."
                    + " Keep the TOP quarter completely clear (plain backdrop) for a bold headline, and the BOTTOM fifth"
                    + " clear for a pill-shaped button.";
            case OFFER_CARD, GRID_TILE -> "A tall product-grid card. Put the products in the upper-middle of the frame,"
                    + " filling about half of it. Keep the BOTTOM 35% clear and plain because the offer, headline and"
                    + " button are drawn there.";
            case CIRCLE -> "A round category icon. One product (or a tight pair) centred and filling about 70% of the"
                    + " frame, on a plain soft backdrop, nothing near the edges because it is cropped to a circle.";
        };
    }

    /** Different looks for the pictures of one "Generate", so staff have a real choice. */
    private static final List<String> LOOKS = List.of(
            "PODIUM STUDIO: the products standing on two or three smooth cylindrical podiums of different heights,"
                    + " in front of a softly curved wall (an arch or a wave line across the backdrop), like a premium"
                    + " marketplace campaign tile.",
            "HERO SPOTLIGHT: the products on a single glossy pedestal with a soft spotlight glow behind them and a few"
                    + " floating accents around them (petals, sparkles, glossy droplets), energetic and premium.",
            "FLAT-LAY SALE: a styled top-down flat-lay of the products neatly arranged with beauty props (brushes, nail"
                    + " polish bottles, cotton pads, flower petals) on a smooth single-colour surface, bright and tidy.");

    static String look(int variant) {
        return LOOKS.get(Math.floorMod(variant, LOOKS.size()));
    }

    /**
     * Staff's words, as a mood hint only: one line, at most 160 characters, with anything asking for text, logos,
     * prices or other rules taken out (the instruction already says what the picture must and must not have).
     */
    static String moodHint(String brief) {
        if (brief == null) return null;
        String t = brief.replaceAll("[\\r\\n\\t]+", " ").replaceAll("[\"“”`]", "").replaceAll("\\s+", " ").strip();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String banned : List.of("ignore", "instruction", "prompt", "write ", "text", "word", "letter", "logo",
                "watermark", "price", "caption", "headline", "slogan", "say ")) {
            if (lower.contains(banned)) {
                // keep only the part before the first rule-like phrase: the mood words staff usually lead with
                int at = lower.indexOf(banned);
                t = t.substring(0, at).strip();
                lower = t.toLowerCase(Locale.ROOT);
            }
        }
        t = t.replaceAll("[,.;:!\\-–]+$", "").strip();
        if (t.length() > 160) t = t.substring(0, 160).strip();
        return t.isBlank() ? null : t;
    }

    /** The answer shape for {@link #words}: three suggestions, each a headline, a sub-line and a button. */
    static java.util.Map<String, Object> wordsSchema() {
        java.util.Map<String, Object> str = java.util.Map.of("type", "STRING");
        java.util.Map<String, Object> one = java.util.Map.of("type", "OBJECT",
                "properties", java.util.Map.of("headline", str, "subline", str, "cta", str),
                "required", List.of("headline", "subline", "cta"));
        return java.util.Map.of("type", "OBJECT",
                "properties", java.util.Map.of("suggestions", java.util.Map.of("type", "ARRAY", "items", one)),
                "required", List.of("suggestions"));
    }

    /**
     * Sales words for a banner (owner, 2026-10-31): short, punchy, marketplace-style, in the shop's Nairobi voice, from
     * what the banner sells. {@code products} are "name — KSh price (deal KSh x)"; {@code hint} is staff's optional topic.
     */
    static String words(PromoBanner.Placement placement, List<String> products, String offer, String theme,
                        String hint) {
        int headlineMax = placement == PromoBanner.Placement.HERO || placement == PromoBanner.Placement.STRIP ? 38 : 26;
        StringBuilder p = new StringBuilder();
        p.append("You write the words on promotional banners for Trendysh Accosmetic, a beauty-supplies shop in")
                .append(" Nairobi selling to salons and beauty lovers (nails, lashes, brows, makeup, skin care, tools).")
                .append(" Write like the best online marketplace sale banners: short, punchy, benefit-led and urgent.\n");
        p.append("Banner place: ").append(switch (placement) {
            case HERO -> "the big home-page banner";
            case STRIP -> "a wide campaign strip";
            case TILE -> "a promo tile among products";
            case OFFER_CARD -> "the first card of the 'On offer today' row";
            case GRID_TILE -> "a tile in the product grid";
            case CIRCLE -> "a category circle";
        }).append(".\n");
        if (!products.isEmpty()) p.append("It sells: ").append(String.join("; ", products)).append(".\n");
        if (offer != null && !offer.isBlank()) p.append("Its offer badge already says: ").append(offer).append(".\n");
        if (theme != null && !theme.isBlank()) p.append("Occasion: ").append(theme).append(".\n");
        String topic = moodHint(hint);
        if (topic != null) p.append("The shop's idea for it (a starting point, not words to copy): ").append(topic).append(".\n");
        p.append("Give 3 different suggestions. Each has:\n")
                .append("- headline: at most ").append(headlineMax).append(" characters, the strongest selling idea")
                .append(" (e.g. 'Salon-ready nails for less', 'Lash restock week'); may end with ONE fitting emoji.\n")
                .append("- subline: at most 70 characters, one concrete reason to buy now (delivery across Nairobi,")
                .append(" wholesale prices, new stock, the offer); no emoji.\n")
                .append("- cta: at most 14 characters, an action ('Shop now', 'Grab the deal', 'Restock now').\n")
                .append("Use simple English; a light Kenyan touch (a Sheng or Swahili word) is welcome in at most one")
                .append(" suggestion. Do not repeat the offer badge's words, do not invent prices or discounts, no")
                .append(" ALL CAPS, no hashtags, no quotation marks.");
        return p.toString();
    }

    /**
     * The whole instruction (owner, 2026-11-06: "a combination of all those in the best way", from a hand-written prompt
     * the shop liked). It is written from the whole banner: the exact products and what they are for, a scene that shows
     * them in use (lashes on an eye, polish on nails), the banner's own colour so the picture melts into it, where the
     * words and the deal cards sit so those parts stay calm, the deals' energy, the occasion and the words' idea. Each
     * picture of one request takes a different concept. The shop's own direction comes first, within the rules.
     */
    static String picture(Brief b) {
        StringBuilder p = new StringBuilder();
        p.append("Create a professional, photorealistic beauty advertisement picture for Trendysh Accosmetic, a")
                .append(" beauty-supplies shop in Nairobi, Kenya, using the attached photos as the exact product")
                .append(" references. It is the background picture of a web banner: the shop draws its own headline,")
                .append(" prices and buttons over it, so the picture itself has NO text at all.\n\n");

        p.append("PRODUCT DETAILS: ");
        if (!b.products().isEmpty()) {
            p.append("the attached photos are the real products: ").append(String.join("; ", b.products())).append('.')
                    .append(" Show these exact products, sharp and recognisable, preserving their original packaging,")
                    .append(" shapes, arrangement, colours and labels. Do not replace them with generic look-alikes,")
                    .append(" do not invent other brands, and clean away any messy background from the photos.\n");
        } else {
            p.append("the attached photo shows the kind of product sold; keep it authentic and recognisable.\n");
        }
        if (!b.categories().isEmpty()) {
            p.append("They are ").append(String.join(", ", b.categories())).append(" products.\n");
        }

        p.append("\nCONCEPT: ").append(concept(b.variant(), scene(b.categories()))).append('\n');

        p.append("\nBACKGROUND AND LAYOUT: ").append(layout(b)).append('\n');

        p.append("\nCOLOUR: the banner's colour is ").append(b.color())
                .append(". Build the backdrop from this exact colour and gentle tints and shades of it, so the picture")
                .append(" blends seamlessly into the banner around it; let the products' own colours pop against it.\n");

        if (b.deals() != null && !b.deals().isBlank()) {
            p.append("\nSALE ENERGY: the campaign is a sale (").append(b.deals())
                    .append("). Make it feel exciting and generous, a must-shop moment, through light, movement and")
                    .append(" props, never through written numbers or signs.\n");
        }
        if (b.theme() != null && !b.theme().isBlank()) {
            p.append("\nOCCASION: ").append(b.theme()).append("; a few small props may hint at it, never covering the products.\n");
        }
        if (b.idea() != null && !b.idea().isBlank()) {
            p.append("\nTHE BANNER'S MESSAGE (for the mood only, never written in the picture): ").append(b.idea()).append('\n');
        }
        String own = direction(b.brief());
        if (own != null) {
            p.append("\nTHE SHOP'S OWN DIRECTION (follow it, within the rules below): ").append(own).append('\n');
        }

        p.append("\nADVERTISING STYLE: professional cosmetic photography, realistic skin texture, sharp product")
                .append(" details, beautiful studio lighting, vibrant colours, a clean premium layout. It should look")
                .append(" like a real beauty-brand campaign, attractive enough to stop someone scrolling and make them")
                .append(" want to buy.\n");
        p.append("\nIMPORTANT: preserve the original products' colours, shapes and packaging. No words, letters, numbers,")
                .append(" prices, logos (other than those printed on the products), badges, watermarks, frames or borders.")
                .append(" Any person is a dark-skinned African woman with natural, realistic features. Keep the calm")
                .append(" areas described in BACKGROUND AND LAYOUT truly calm.");
        return p.toString();
    }

    /** Everything about a banner that its picture is made from. */
    record Brief(PromoBanner.Placement placement, List<String> products, List<String> categories, String deals,
                 String idea, String theme, String color, String heroSide, boolean hasCards, String brief, int variant) {}

    /** How the products are shown in use, from what they are. */
    static String scene(List<String> categories) {
        String all = String.join(" ", categories).toLowerCase(Locale.ROOT);
        if (all.contains("lash")) {
            return "a close-up of a woman's eye wearing long, curled lashes styled with these exact products, believable"
                    + " and delicate, never covering the eye";
        }
        if (all.contains("brow")) {
            return "a close-up of a woman's perfectly shaped, groomed brows done with these products";
        }
        if (all.contains("nail") || all.contains("manicure") || all.contains("pedicure")) {
            return "elegant hands with a flawless, glossy manicure done with these exact products";
        }
        if (all.contains("wax") || all.contains("spa") || all.contains("skin")) {
            return "smooth, glowing skin in a calm, luxurious spa moment using these products";
        }
        if (all.contains("make") || all.contains("lip") || all.contains("face")) {
            return "a radiant face with flawless makeup done with these exact products";
        }
        if (all.contains("equipment") || all.contains("furniture") || all.contains("trolley") || all.contains("lamp")
                || all.contains("salon")) {
            return "a bright, modern professional beauty salon where these exact items are in use";
        }
        return "the products being used by a beauty professional, showing the beautiful result";
    }

    /**
     * The concepts for the pictures of one request, so each is a different idea. The first is the one the shop's own
     * prompt asked for: the product beside how it looks when used.
     */
    private static final List<String> CONCEPTS = List.of(
            "SHOWN AND WORN: {scene}, as the large main focus, with the original products placed right beside it so"
                    + " customers see both what they buy and how it looks; add a smaller portrait of the model for the"
                    + " overall look.",
            "HERO STILL LIFE: the products as the sole stars on smooth sculptural podiums of different heights, with"
                    + " soft spotlight glow and a few floating accents (petals, sparkles, glossy droplets) around them.",
            "IN THE PROFESSIONAL'S HANDS: a beauty professional at work in a chic salon, {scene}, with the products"
                    + " clearly visible on the station in front of her.",
            "EDITORIAL FLAT LAY: a styled top-down arrangement of the products with matching beauty props on a smooth"
                    + " surface, tidy, bright and premium, with a slice of {scene} at one edge.",
            "BOLD CLOSE-UP: an extreme, magazine-cover close-up of {scene}, dramatic and glossy, with the products large"
                    + " in the foreground.");

    static String concept(int variant, String scene) {
        return CONCEPTS.get(Math.floorMod(variant, CONCEPTS.size())).replace("{scene}", scene);
    }

    /** Where the picture's subject goes and which parts stay calm, matching what the shop draws over it. */
    static String layout(Brief b) {
        String side = b.heroSide() == null ? "RIGHT" : b.heroSide();
        return switch (b.placement()) {
            case HERO, STRIP -> b.hasCards()
                    ? "a wide, short web banner. Put the whole subject (the scene and the products) inside the "
                    + side + " 30% of the frame, large and sharp. The other 70% is covered by white product cards"
                    + " and the headline, so it must be a smooth, softly lit continuation of the backdrop with nothing"
                    + " in it: no objects, no people, no busy patterns."
                    : "a wide, short web banner. Put the subject in the RIGHT 45% of the frame, large and sharp. The"
                    + " LEFT 55% must be a smooth continuation of the backdrop with nothing in it, because the"
                    + " headline, offer and button are drawn there.";
            case TILE -> "a square-ish promo tile. Put the subject in the MIDDLE, filling about half of it. Keep the TOP"
                    + " quarter and the BOTTOM fifth plain backdrop for the headline and the button.";
            case OFFER_CARD, GRID_TILE -> "a tall product-grid card. Put the subject in the upper-middle, filling about"
                    + " half of it. Keep the BOTTOM 35% plain because the offer, headline and button are drawn there.";
            case CIRCLE -> "a round category icon. One product or a tight pair centred and filling about 70% of the frame,"
                    + " on a plain backdrop, nothing near the edges because it is cropped to a circle.";
        };
    }

    /**
     * The shop's own words for the picture, kept whole (a full prompt is welcome, up to 1,500 characters) but with any
     * request to write text, logos or prices on the picture taken out, as the shop draws those itself.
     */
    static String direction(String brief) {
        if (brief == null) return null;
        String t = brief.replace('\u0000', ' ').replaceAll("[\\t ]+", " ").strip();
        List<String> kept = new java.util.ArrayList<>();
        for (String sentence : t.split("(?<=[.!?\\n])\\s*")) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            boolean asksForText = lower.matches(".*\\b(write|text|caption|slogan|headline|logo|watermark|price tag|"
                    + "hashtag|ignore (the|all|previous)).*");
            if (!asksForText && !sentence.isBlank()) kept.add(sentence.strip());
        }
        String out = String.join(" ", kept).strip();
        if (out.length() > 1500) out = out.substring(0, 1500).strip();
        return out.isBlank() ? null : out;
    }
}
