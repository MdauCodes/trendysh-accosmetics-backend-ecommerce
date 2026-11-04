package com.nbh.backend.banners;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Takes the owner out of her green screen (owner, 2026-11-04), so she can stand on any banner colour and be moved about on
 * it. Green because nothing she wears is green: her hot-pink blouse on a magenta backdrop could not be told apart. A pixel
 * goes by how far its green stands above its red and blue; at her edges it fades, and what green is left on her hair and
 * sleeves is taken off. A picture whose screen did not come out clean is refused rather than shown with holes or a fringe.
 */
final class PoseCutout {

    private PoseCutout() {}

    /** The backdrop new poses are made on. */
    static final String SCREEN_HEX = "#00B140";

    /** How far green must stand above red and blue: below LOW she is solid, above HIGH she is gone. */
    private static final int LOW = 22;
    private static final int HIGH = 62;
    /** Dark green (a shadow on the screen) goes too, but not black clothes, where a little noise is a big ratio. */
    private static final int DARK_GREEN_MIN = 40;
    private static final double DARK_GREEN_RATIO = 0.38;
    /** A clean result: nearly all of the top and sides gone, and between these shares of the whole picture left. */
    private static final double EDGE_GONE = 0.85;
    private static final double SHE_MIN = 0.08;
    private static final double SHE_MAX = 0.85;

    /** The picture as a PNG with a transparent background, or empty when the green screen was not clean enough. */
    static Optional<byte[]> cut(byte[] picture) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(picture));
            if (img == null) return Optional.empty();
            int w = img.getWidth(), h = img.getHeight();
            int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
            int[] alpha = new int[rgb.length];
            for (int i = 0; i < rgb.length; i++) alpha[i] = alphaOf(rgb[i]);
            int[] soft = soften(alpha, w, h);

            long left = 0, edge = 0, edgeGone = 0;
            int band = Math.max(2, Math.min(w, h) / 40);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (soft[i] > 127) left++;
                    if (y < band || x < band || x >= w - band) {
                        edge++;
                        if (soft[i] < 64) edgeGone++;
                    }
                }
            }
            double share = (double) left / rgb.length;
            if ((double) edgeGone / edge < EDGE_GONE || share < SHE_MIN || share > SHE_MAX) return Optional.empty();

            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] argb = new int[rgb.length];
            for (int i = 0; i < rgb.length; i++) argb[i] = (soft[i] << 24) | withoutGreen(rgb[i]);
            out.setRGB(0, 0, w, h, argb, 0, w);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return Optional.of(bytes.toByteArray());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static int alphaOf(int pixel) {
        int r = (pixel >> 16) & 0xff, g = (pixel >> 8) & 0xff, b = pixel & 0xff;
        int above = g - Math.max(r, b);
        if (above <= 0) return 255;
        if (g >= DARK_GREEN_MIN && (double) above / g >= DARK_GREEN_RATIO) return 0;
        if (above <= LOW) return 255;
        if (above >= HIGH) return 0;
        return 255 - (above - LOW) * 255 / (HIGH - LOW);
    }

    /** The pixel's colour with its green brought down to its red or blue, so no green shows at her edges. */
    private static int withoutGreen(int pixel) {
        int r = (pixel >> 16) & 0xff, g = (pixel >> 8) & 0xff, b = pixel & 0xff;
        return (r << 16) | (Math.min(g, Math.max(r, b)) << 8) | b;
    }

    /** One pixel's softness at the edges: each takes the average of itself and its neighbours where they differ. */
    private static int[] soften(int[] a, int w, int h) {
        int[] out = a.clone();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int sum = 0, min = 255, max = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int v = a[i + dy * w + dx];
                        sum += v;
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                    }
                }
                if (min != max) out[i] = sum / 9;
            }
        }
        return out;
    }
}
