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
        String knee = OwnerPosePrompts.prompt(pose(OwnerPose.Framing.KNEE, OwnerPose.Side.LEFT), null);
        assertThat(knee).contains("feet and shoes are NOT in the picture").doesNotContain("to her shoes");
        String full = OwnerPosePrompts.prompt(pose(OwnerPose.Framing.FULL, OwnerPose.Side.LEFT), null);
        assertThat(full).contains("to her shoes");
    }

    @Test
    void sheTurnsTowardTheDealsOnTheOtherSide() {
        assertThat(OwnerPosePrompts.pose(OwnerPose.Mood.LOOK, OwnerPose.Side.LEFT)).contains("to the RIGHT side");
        assertThat(OwnerPosePrompts.pose(OwnerPose.Mood.LOOK, OwnerPose.Side.RIGHT)).contains("to the LEFT side");
        assertThat(OwnerPosePrompts.prompt(pose(OwnerPose.Framing.WAIST, OwnerPose.Side.LEFT), "holding a gel bottle"))
                .contains("#C8157A").contains("Also: holding a gel bottle.");
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
