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

    @Test
    void theWrongBackdropIsNoticedAndAHandAtTheEdgeIsNot() throws Exception {
        BufferedImage img = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 400);
        g.setColor(Color.BLACK);
        g.fillRect(280, 100, 20, 40); // a hand touching the side
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        var picture = new GeminiImageClient.Image(out.toByteArray(), "image/png");
        assertThat(OwnerPoseService.backdropMatch(picture, "#FFFFFF")).isGreaterThan(OwnerPoseService.BACKDROP_ENOUGH);
        assertThat(OwnerPoseService.backdropMatch(picture, "#C8157A")).isLessThan(OwnerPoseService.BACKDROP_ENOUGH);
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
}
