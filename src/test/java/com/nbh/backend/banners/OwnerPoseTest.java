package com.nbh.backend.banners;

import com.nbh.backend.imagecleanup.GeminiImageClient;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The owner's poses (owner, 2026-11-04): what Gemini is asked, the backdrop colour measured, the usual layout. */
class OwnerPoseTest {

    private static OwnerPose pose(OwnerPose.Framing framing, OwnerPose.Side side) {
        OwnerPose p = new OwnerPose();
        p.setMood(OwnerPose.Mood.LOOK);
        p.setFraming(framing);
        p.setSide(side);
        p.setOutfit(OwnerPose.Outfit.PINK_BLOUSE);
        p.setBackdropHex("#C8157A");
        return p;
    }

    @Test
    void feetAreLeftOutUnlessTheFramingIsFullHeight() {
        String knee = OwnerPosePrompts.prompt(pose(OwnerPose.Framing.KNEE, OwnerPose.Side.LEFT), null, 0);
        assertThat(knee).contains("feet and shoes are NOT in the picture").doesNotContain("to her shoes");
        String full = OwnerPosePrompts.prompt(pose(OwnerPose.Framing.FULL, OwnerPose.Side.LEFT), null, 0);
        assertThat(full).contains("to her shoes");
    }

    @Test
    void sheTurnsTowardTheDealsOnTheOtherSide() {
        assertThat(OwnerPosePrompts.pose(OwnerPose.Mood.LOOK, OwnerPose.Side.LEFT, 0)).contains("to the RIGHT of the frame")
                .doesNotContain("{");
        assertThat(OwnerPosePrompts.pose(OwnerPose.Mood.LOOK, OwnerPose.Side.RIGHT, 0)).contains("to the LEFT of the frame");
    }

    @Test
    void everyMoodHasDifferentDirectionsSoVariantsAreNotRepeats() {
        for (OwnerPose.Mood mood : OwnerPose.Mood.values()) {
            if (mood == OwnerPose.Mood.OWN) continue;
            assertThat(OwnerPosePrompts.directions(mood)).hasSize(OwnerPosePrompts.VARIANTS).doesNotHaveDuplicates();
            java.util.Set<String> prompts = new java.util.HashSet<>();
            OwnerPose p = pose(OwnerPose.Framing.KNEE, OwnerPose.Side.LEFT);
            p.setMood(mood);
            for (int i = 0; i < OwnerPosePrompts.VARIANTS; i++) prompts.add(OwnerPosePrompts.prompt(p, null, i));
            assertThat(prompts).hasSize(OwnerPosePrompts.VARIANTS);
        }
    }

    @Test
    void herOwnNoteReplacesTheMoodsPoseAndVariantsStillDiffer() {
        OwnerPose p = pose(OwnerPose.Framing.WAIST, OwnerPose.Side.LEFT);
        String one = OwnerPosePrompts.prompt(p, "holding her waist", 0);
        assertThat(one).contains("comes first").contains("holding her waist").contains("#C8157A")
                .doesNotContain("palm open and up");
        assertThat(OwnerPosePrompts.prompt(p, "holding her waist", 1)).isNotEqualTo(one);
    }

    @Test
    void theShopsOwnWordsReplaceEachSettingAndVariantsStillDiffer() {
        OwnerPose p = pose(OwnerPose.Framing.KNEE, OwnerPose.Side.LEFT);
        var x = new OwnerPosePrompts.Extras(null, "holding her waist", "a stunned stare", "from the floor up", "neon light",
                "a gel polish bottle", "a green-free red suit", "from far away", "in the middle, facing us");
        String one = OwnerPosePrompts.prompt(p, null, 0, x);
        assertThat(one).contains("holding her waist", "a stunned stare", "from the floor up", "neon light", "a gel polish bottle",
                "a green-free red suit", "from far away", "in the middle, facing us")
                .doesNotContain("hot-pink blouse", "palm open and up", "cropped just above the knees");
        assertThat(OwnerPosePrompts.prompt(p, null, 1, x)).isNotEqualTo(one);
    }

    @Test
    void theShopIsNotToldWhoMadeAPoseOrWhatTheyAskedFor() {
        OwnerPose p = pose(OwnerPose.Framing.KNEE, OwnerPose.Side.LEFT);
        p.setStatus(OwnerPose.Status.APPROVED);
        p.setImageUrl("https://img.example.test/green.jpg");
        p.setCutoutUrl("https://img.example.test/cut.png");
        p.setBrief("a private prompt");
        p.setCreatedBy("A Staff Member");
        p.setApprovedBy("Another Staff Member");
        var shop = OwnerPoseService.forShop(p);
        assertThat(shop.brief()).isNull();
        assertThat(shop.createdBy()).isNull();
        assertThat(shop.approvedBy()).isNull();
        assertThat(shop.imageUrl()).isEqualTo("https://img.example.test/cut.png");
        assertThat(shop.cutoutUrl()).isEqualTo("https://img.example.test/cut.png");
    }

    @Test
    void anOwnMoodWithoutWordsDrawsNoPresetPose() {
        OwnerPose p = pose(OwnerPose.Framing.KNEE, OwnerPose.Side.LEFT);
        p.setMood(OwnerPose.Mood.OWN);
        assertThat(OwnerPosePrompts.prompt(p, "doing a little dance", 0)).contains("doing a little dance").doesNotContain("palm open");
    }

    @Test
    void everyFramingHasAShapeAndAUsualLayout() {
        for (OwnerPose.Framing f : OwnerPose.Framing.values()) {
            assertThat(OwnerPosePrompts.aspectRatio(f)).isNotBlank();
            assertThat(OwnerPosePrompts.framing(f)).startsWith("Framing:");
            assertThat(OwnerPoseService.defaults(pose(f, OwnerPose.Side.RIGHT)).facing()).isEqualTo("LEFT");
        }
    }

    @Test
    void measuringAcceptsFractionsAndThousandthsAndRefusesNonsense() throws Exception {
        var fallback = OwnerPoseService.defaults(pose(OwnerPose.Framing.WAIST, OwnerPose.Side.LEFT));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var thousandths = OwnerPoseService.measured(json.readTree("""
                {"faceBox":{"x":400,"y":100,"w":200,"h":250},"safeBox":{"x":100,"y":50,"w":800,"h":600},"focal":{"x":500,"y":200}}"""), fallback);
        assertThat(thousandths.measured()).isTrue();
        assertThat(thousandths.faceBox().x()).isEqualTo(0.4);
        var fractions = OwnerPoseService.measured(json.readTree("""
                {"faceBox":{"x":0.4,"y":0.1,"w":0.2,"h":0.25},"safeBox":{"x":0.45,"y":0.15,"w":0.1,"h":0.1},"focal":{"x":0.5,"y":0.2}}"""), fallback);
        assertThat(fractions.measured()).isTrue();
        assertThat(fractions.safeBox().w()).isGreaterThanOrEqualTo(fractions.faceBox().w());
        assertThat(OwnerPoseService.measured(json.readTree("{}"), fallback)).isEqualTo(fallback);
    }

    private static byte[] onScreen(int r, int g, int b) throws Exception {
        BufferedImage img = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D gfx = img.createGraphics();
        gfx.setColor(new Color(r, g, b));
        gfx.fillRect(0, 0, 300, 400);
        gfx.setColor(new Color(0xE9, 0x1E, 0x8C)); // her blouse
        gfx.fillRect(100, 100, 100, 150);
        gfx.setColor(new Color(8, 12, 8)); // her skirt, running off the bottom
        gfx.fillRect(100, 250, 100, 150);
        gfx.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void sheIsCutOutOfTheGreenScreenWithHerClothesWhole() throws Exception {
        byte[] png = PoseCutout.cut(onScreen(0x00, 0xB1, 0x40)).orElseThrow();
        BufferedImage cut = ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertThat(cut.getColorModel().hasAlpha()).isTrue();
        assertThat(cut.getRGB(5, 5) >>> 24).isZero();
        assertThat(cut.getRGB(150, 150) >>> 24).isEqualTo(255);
        assertThat(cut.getRGB(150, 300) >>> 24).as("a black skirt is not a hole").isEqualTo(255);
        assertThat((cut.getRGB(150, 150) >> 8) & 0xff).as("her blouse keeps its colour").isEqualTo(0x1E);
    }

    @Test
    void aPictureWithoutACleanScreenIsRefused() throws Exception {
        assertThat(PoseCutout.cut(onScreen(0xC8, 0x15, 0x7A))).isEmpty();
        assertThat(PoseCutout.cut(new byte[] {1, 2, 3})).isEmpty();
    }

    @Test
    void anArrangementKeepsOnlyWhatTheShopCanDraw() {
        var messy = new CampaignDealService.Arrangement("not a colour", null,
                new CampaignDealService.LaptopPlan(java.util.List.of("pose", "pose", "cards"),
                        new CampaignDealService.PosePlace(999d, -999d, 9d)),
                new CampaignDealService.PhonePlan("middle", true, null), "cloud", "yellow", "maybe");
        var tidy = CampaignDealService.tidy(messy);
        assertThat(tidy.color()).isNull();
        assertThat(tidy.laptop().order()).isNull();
        assertThat(tidy.laptop().pose()).isEqualTo(new CampaignDealService.PosePlace(120d, -60d, 1.8));
        assertThat(tidy.phone().poseSide()).isNull();
        assertThat(tidy.badge()).isNull();
        assertThat(tidy.accent()).isNull();
        assertThat(tidy.torn()).isNull();
        assertThat(CampaignDealService.tidy(new CampaignDealService.Arrangement(null, false, null, null, null, null, null))).isNull();
        var good = new CampaignDealService.Arrangement("#3A0B2C", true,
                new CampaignDealService.LaptopPlan(java.util.List.of("cards", "pose", "words"), null), null, "torn", "#FFD60A", "off");
        assertThat(CampaignDealService.tidy(good)).isEqualTo(good);
    }

    @Test
    void aHomeBannerWithAPoseNeedsNoSeparatePicture() {
        PromoBanner b = new PromoBanner();
        b.setPlacement(PromoBanner.Placement.HERO);
        assertThat(PromoBannerService.missing(b, java.util.List.of(), true)).anyMatch(m -> m.contains("picture"));
        b.setPoseId(java.util.UUID.randomUUID());
        assertThat(PromoBannerService.missing(b, java.util.List.of(), true)).noneMatch(m -> m.contains("picture"));
    }

    @Test
    void theBackdropColourIsReadFromTheEdges() throws Exception {
        BufferedImage img = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xB0, 0x20, 0x6A));
        g.fillRect(0, 0, 300, 400);
        g.setColor(Color.BLACK); // her skirt, running off the bottom edge
        g.fillRect(100, 150, 100, 250);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);

        var m = OwnerPoseService.measure(new GeminiImageClient.Image(out.toByteArray(), "image/png"));
        assertThat(m.width()).isEqualTo(300);
        assertThat(m.height()).isEqualTo(400);
        assertThat(m.edgeHex()).isEqualTo("#B0206A");
    }

    @Test
    void aKneeUpPoseOnTheLeftFacesRightAndMayBeCutBelowItsHands() {
        var l = OwnerPoseService.defaults(pose(OwnerPose.Framing.KNEE, OwnerPose.Side.LEFT));
        assertThat(l.figureSide()).isEqualTo("LEFT");
        assertThat(l.facing()).isEqualTo("RIGHT");
        assertThat(l.cropFloor()).isGreaterThanOrEqualTo(l.safeBox().y() + l.safeBox().h());
        assertThat(OwnerPoseService.read(OwnerPoseService.write(l))).isEqualTo(l);
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void anUploadedPictureOnASeeThroughBackgroundIsUsedAsItIs() throws Exception {
        BufferedImage cut = new BufferedImage(300, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cut.createGraphics();
        g.setColor(new Color(0xE9, 0x1E, 0x8C));
        g.fillRect(100, 100, 100, 300); // her, standing on the bottom edge; the rest stays see-through
        g.dispose();
        assertThat(PoseCutout.alreadyCut(png(cut))).isTrue();
    }

    @Test
    void anUploadedPhotoWithAShopBehindHerIsNotTakenForACutOut() throws Exception {
        assertThat(PoseCutout.alreadyCut(onScreen(0xC8, 0x15, 0x7A))).isFalse();
        BufferedImage solid = new BufferedImage(300, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = solid.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 400); // a PNG that can be see-through but is not
        g.dispose();
        assertThat(PoseCutout.alreadyCut(png(solid))).isFalse();
        assertThat(PoseCutout.alreadyCut(new byte[] {1, 2, 3})).isFalse();
    }
}
