/*  Copyright (C) 2026 Zebsi235

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Turns an arbitrary photo into the watch's 96×39 one-bit image.
 *
 * <p>The pipeline is: crop/scale to the panel's aspect, reduce to luminance, then
 * either diffuse the error (Floyd–Steinberg, good for photos) or hard-threshold
 * (good for logos and text). The grayscale and one-bit steps take and return
 * plain arrays so they are unit-tested; only the two Bitmap adapters at the
 * bottom touch {@code android.graphics}.
 */
public final class F91KeplerImageConverter {
    private F91KeplerImageConverter() {
    }

    /** Midpoint of the 0..255 luminance range — the default threshold. */
    public static final int DEFAULT_THRESHOLD = 128;

    /**
     * BT.601 luma of each ARGB pixel, 0..255. Fully transparent pixels count as
     * black so a letterboxed or alpha-masked source does not light up the panel.
     */
    public static int[] luminance(final int[] argb) {
        final int[] lum = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            final int p = argb[i];
            final int a = (p >>> 24) & 0xFF;
            if (a == 0) {
                lum[i] = 0;
                continue;
            }
            final int r = (p >> 16) & 0xFF;
            final int g = (p >> 8) & 0xFF;
            final int b = p & 0xFF;
            lum[i] = (r * 299 + g * 587 + b * 114) / 1000;
        }
        return lum;
    }

    /** Hard threshold: a pixel is lit when its luminance reaches {@code threshold}. */
    public static boolean[] threshold(final int[] lum, final int threshold, final boolean invert) {
        final boolean[] lit = new boolean[lum.length];
        for (int i = 0; i < lum.length; i++) {
            lit[i] = (lum[i] >= threshold) != invert;
        }
        return lit;
    }

    /**
     * Floyd–Steinberg error diffusion at the 50 % threshold. Works on a copy of
     * {@code lum} so the caller can re-run with different settings, and clamps the
     * accumulated error to keep it from running away on saturated images.
     */
    public static boolean[] dither(final int[] lum, final int width, final int height,
                                   final boolean invert) {
        final int[] work = new int[lum.length];
        System.arraycopy(lum, 0, work, 0, lum.length);

        final boolean[] lit = new boolean[lum.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int i = y * width + x;
                final int old = work[i];
                final boolean on = old >= DEFAULT_THRESHOLD;
                lit[i] = on != invert;
                final int error = old - (on ? 255 : 0);
                // 7/16 right, 3/16 below-left, 5/16 below, 1/16 below-right.
                if (x + 1 < width) {
                    spread(work, i + 1, error * 7 / 16);
                }
                if (y + 1 < height) {
                    if (x > 0) {
                        spread(work, i + width - 1, error * 3 / 16);
                    }
                    spread(work, i + width, error * 5 / 16);
                    if (x + 1 < width) {
                        spread(work, i + width + 1, error / 16);
                    }
                }
            }
        }
        return lit;
    }

    private static void spread(final int[] work, final int index, final int delta) {
        work[index] = Math.max(-255, Math.min(510, work[index] + delta));
    }

    // --- Bitmap adapters ----------------------------------------------------

    /**
     * Render {@code source} into a 96×39 bitmap. {@code fill} crops the overflowing
     * axis so the panel is fully covered, with {@code pan} (0..1) sliding the crop
     * window; otherwise the image is letterboxed on black. The result is always
     * exactly panel-sized, so the caller never has to think about scaling again.
     */
    public static Bitmap fitToPanel(final Bitmap source, final boolean fill, final float pan) {
        final int width = F91KeplerConstants.IMAGE_WIDTH;
        final int height = F91KeplerConstants.IMAGE_HEIGHT;
        final Bitmap panel = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(panel);
        canvas.drawColor(Color.BLACK);

        final float scale = fill
                ? Math.max((float) width / source.getWidth(), (float) height / source.getHeight())
                : Math.min((float) width / source.getWidth(), (float) height / source.getHeight());
        final float drawWidth = source.getWidth() * scale;
        final float drawHeight = source.getHeight() * scale;
        final float slide = Math.max(0f, Math.min(1f, pan));
        final float left = drawWidth > width ? -(drawWidth - width) * slide : (width - drawWidth) / 2f;
        final float top = drawHeight > height ? -(drawHeight - height) * slide : (height - drawHeight) / 2f;

        canvas.drawBitmap(source, null, new RectF(left, top, left + drawWidth, top + drawHeight),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return panel;
    }

    /**
     * Convert a panel-sized bitmap to lit flags, ready for
     * {@link F91KeplerImageCodec#packImage}.
     */
    public static boolean[] toBits(final Bitmap panel, final boolean useDither,
                                   final int thresholdLevel, final boolean invert) {
        final int width = panel.getWidth();
        final int height = panel.getHeight();
        final int[] argb = new int[width * height];
        panel.getPixels(argb, 0, width, 0, 0, width, height);
        final int[] lum = luminance(argb);
        return useDither ? dither(lum, width, height, invert)
                         : threshold(lum, thresholdLevel, invert);
    }

    /**
     * Blow the one-bit result up by {@code scale} with nearest-neighbour sampling,
     * so the preview shows exactly the pixels the watch will light.
     */
    public static Bitmap toPreview(final boolean[] lit, final int scale) {
        final int width = F91KeplerConstants.IMAGE_WIDTH;
        final int height = F91KeplerConstants.IMAGE_HEIGHT;
        if (lit == null || lit.length != width * height) {
            throw new IllegalArgumentException(
                    "expected " + (width * height) + " pixels, got " + (lit == null ? -1 : lit.length));
        }
        final int[] argb = new int[lit.length];
        for (int i = 0; i < lit.length; i++) {
            argb[i] = lit[i] ? Color.WHITE : Color.BLACK;
        }
        final Bitmap exact = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
        if (scale <= 1) {
            return exact;
        }
        return Bitmap.createScaledBitmap(exact, width * scale, height * scale, false);
    }
}
