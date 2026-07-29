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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Pins the image frame to the firmware's SSD1306 layout: page-major bytes,
 * LSB = top row of the page, row 39 never lit. Everything here is deliberately
 * free of {@code android.graphics} — unit tests run against the stub android.jar,
 * where Bitmap calls silently return defaults, so a Bitmap-based test would pass
 * without testing anything.
 */
public class F91KeplerImageCodecTest {

    private static final int W = F91KeplerConstants.IMAGE_WIDTH;   // 96
    private static final int H = F91KeplerConstants.IMAGE_HEIGHT;  // 39

    private static void light(final boolean[] lit, final int x, final int y) {
        lit[y * W + x] = true;
    }

    /** JUnit 4's assertArrayEquals has no boolean[] overload, hence this. */
    private static void assertBitsEqual(final boolean[] expected, final boolean[] actual) {
        assertEquals("pixel count", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail("pixel " + i + ": expected " + expected[i] + " but was " + actual[i]);
            }
        }
    }

    @Test
    public void packImage_putsEachPixelInItsPageMajorBit() {
        final boolean[] lit = new boolean[W * H];
        light(lit, 0, 0);    // byte 0, bit 0
        light(lit, 0, 7);    // byte 0, bit 7  (same byte, bottom of page 0)
        light(lit, 0, 8);    // byte 96, bit 0 (first row of page 1)
        light(lit, 95, 38);  // byte 95 + 4*96 = 479, bit 6 (last visible pixel)

        final byte[] frame = F91KeplerImageCodec.packImage(lit);

        assertEquals(F91KeplerConstants.IMAGE_BYTES, frame.length);
        assertEquals((byte) 0x81, frame[0]);
        assertEquals((byte) 0x01, frame[96]);
        assertEquals((byte) 0x40, frame[479]);
        // Nothing else may be touched.
        for (int i = 0; i < frame.length; i++) {
            if (i != 0 && i != 96 && i != 479) {
                assertEquals("byte " + i + " must stay clear", 0, frame[i]);
            }
        }
    }

    @Test
    public void packImage_ofAFullyLitPanelLeavesTheOffscreenRowClear() {
        final boolean[] lit = new boolean[W * H];
        for (int i = 0; i < lit.length; i++) {
            lit[i] = true;
        }
        final byte[] frame = F91KeplerImageCodec.packImage(lit);

        // Pages 0..3 cover rows 0..31 -> every bit set.
        for (int i = 0; i < 4 * W; i++) {
            assertEquals("page " + (i / W), (byte) 0xFF, frame[i]);
        }
        // Page 4 covers rows 32..39, but row 39 is offscreen and must stay 0.
        for (int i = 4 * W; i < F91KeplerConstants.IMAGE_BYTES; i++) {
            assertEquals("page 4 keeps bit 7 clear", (byte) 0x7F, frame[i]);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void packImage_rejectsAWrongPixelCount() {
        F91KeplerImageCodec.packImage(new boolean[W * H - 1]);
    }

    @Test
    public void unpackImage_roundTripsThroughPackImage() {
        final boolean[] lit = new boolean[W * H];
        // A pattern that touches every page and both bit ends.
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                lit[y * W + x] = ((x * 3 + y * 5) & 7) < 3;
            }
        }
        assertBitsEqual(lit,
                F91KeplerImageCodec.unpackImage(F91KeplerImageCodec.packImage(lit)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unpackImage_rejectsAWrongSizedFrame() {
        F91KeplerImageCodec.unpackImage(new byte[479]);
    }

    @Test
    public void xor8_isTheXorOfEveryByte() {
        assertEquals(0, F91KeplerImageCodec.xor8(new byte[]{1, 2, 3}));   // 1^2^3 == 0
        assertEquals((byte) 0xFF, F91KeplerImageCodec.xor8(new byte[]{0x0F, (byte) 0xF0}));
        assertEquals(0, F91KeplerImageCodec.xor8(new byte[F91KeplerConstants.IMAGE_BYTES]));
    }

    @Test
    public void luminance_isBt601AndTreatsTransparentAsBlack() {
        final int[] argb = {
                0xFFFFFFFF,  // white
                0xFF000000,  // black
                0xFFFF0000,  // red
                0xFF00FF00,  // green
                0xFF0000FF,  // blue
                0x00FFFFFF,  // transparent white
        };
        assertArrayEquals(new int[]{255, 0, 76, 149, 29, 0},
                F91KeplerImageConverter.luminance(argb));
    }

    @Test
    public void threshold_litFromTheThresholdUpAndInvertFlipsIt() {
        final int[] lum = {0, 127, 128, 255};
        assertBitsEqual(new boolean[]{false, false, true, true},
                F91KeplerImageConverter.threshold(lum, 128, false));
        assertBitsEqual(new boolean[]{true, true, false, false},
                F91KeplerImageConverter.threshold(lum, 128, true));
    }

    @Test
    public void dither_leavesFlatBlackAndWhiteAlone() {
        final int[] black = new int[W * H];
        for (final boolean on : F91KeplerImageConverter.dither(black, W, H, false)) {
            assertFalse("flat black must not light a pixel", on);
        }
        final int[] white = new int[W * H];
        for (int i = 0; i < white.length; i++) {
            white[i] = 255;
        }
        for (final boolean on : F91KeplerImageConverter.dither(white, W, H, false)) {
            assertTrue("flat white must light every pixel", on);
        }
    }

    @Test
    public void dither_breaksFlatMidGrayIntoAMixOfPixels() {
        final int[] gray = new int[W * H];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = 128;
        }
        final boolean[] lit = F91KeplerImageConverter.dither(gray, W, H, false);

        int on = 0;
        for (final boolean b : lit) {
            if (b) {
                on++;
            }
        }
        // The point of error diffusion: mid-gray becomes a roughly even mix
        // rather than a flat block either way.
        assertTrue("expected some lit pixels, got " + on, on > lit.length / 4);
        assertTrue("expected some dark pixels, got " + on, on < lit.length * 3 / 4);
    }

    @Test
    public void dither_invertFlipsEveryPixel() {
        final int[] lum = new int[W * H];
        for (int i = 0; i < lum.length; i++) {
            lum[i] = (i * 37) % 256;
        }
        final boolean[] normal = F91KeplerImageConverter.dither(lum, W, H, false);
        final boolean[] inverted = F91KeplerImageConverter.dither(lum, W, H, true);
        for (int i = 0; i < normal.length; i++) {
            assertTrue("pixel " + i + " should be flipped", normal[i] != inverted[i]);
        }
    }

    @Test
    public void dither_doesNotMutateTheCallersLuminance() {
        final int[] lum = new int[W * H];
        for (int i = 0; i < lum.length; i++) {
            lum[i] = 90;
        }
        final int[] copy = lum.clone();
        F91KeplerImageConverter.dither(lum, W, H, false);
        assertArrayEquals("the settings sliders re-run this on the same array", copy, lum);
    }
}
