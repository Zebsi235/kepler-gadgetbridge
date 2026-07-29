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

/**
 * Packs a 1-bit watch image into the firmware's 480-byte frame (Image Service
 * A3F0, firmware v2.16.0) and back.
 *
 * <p>The frame is literally an SSD1306 framebuffer, so the watch renders it with
 * a plain memcpy: 96 columns × 5 pages, page-major, and within a page the
 * <em>least</em> significant bit is the top pixel row. A lit (white) pixel
 * {@code (x, y)} therefore sets bit {@code y & 7} of byte {@code x + (y / 8) * 96}.
 * The buffer covers 40 rows but the panel only shows 39, so row 39 is always
 * left clear.
 *
 * <p>This lives in the device (contract) package rather than next to the other
 * serializers in {@code F91KeplerProtocol} because both the UI — which converts
 * a photo — and the transport — which uploads and checksums it — need it, and
 * they sit in different packages. It stays free of {@code android.graphics} so
 * it can be unit-tested directly; the Bitmap plumbing is in
 * {@link F91KeplerImageConverter}.
 */
public final class F91KeplerImageCodec {
    private F91KeplerImageCodec() {
    }

    /**
     * Pack a row-major array of lit flags ({@code true} = white) into the
     * 480-byte frame. {@code lit} must hold {@link F91KeplerConstants#IMAGE_WIDTH}
     * × {@link F91KeplerConstants#IMAGE_HEIGHT} entries, indexed
     * {@code y * width + x}.
     */
    public static byte[] packImage(final boolean[] lit) {
        final int width = F91KeplerConstants.IMAGE_WIDTH;
        final int height = F91KeplerConstants.IMAGE_HEIGHT;
        if (lit == null || lit.length != width * height) {
            throw new IllegalArgumentException(
                    "expected " + (width * height) + " pixels, got " + (lit == null ? -1 : lit.length));
        }
        final byte[] frame = new byte[F91KeplerConstants.IMAGE_BYTES];
        for (int y = 0; y < height; y++) {
            final int page = (y / 8) * width;
            final int bit = 1 << (y & 7);
            for (int x = 0; x < width; x++) {
                if (lit[y * width + x]) {
                    frame[page + x] |= (byte) bit;
                }
            }
        }
        return frame;
    }

    /**
     * Inverse of {@link #packImage}, so a stored frame can be shown again without
     * keeping the source photo around. Row 39 is dropped (never visible).
     */
    public static boolean[] unpackImage(final byte[] frame) {
        if (frame == null || frame.length != F91KeplerConstants.IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "expected " + F91KeplerConstants.IMAGE_BYTES + " bytes, got "
                            + (frame == null ? -1 : frame.length));
        }
        final int width = F91KeplerConstants.IMAGE_WIDTH;
        final int height = F91KeplerConstants.IMAGE_HEIGHT;
        final boolean[] lit = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            final int page = (y / 8) * width;
            final int bit = 1 << (y & 7);
            for (int x = 0; x < width; x++) {
                lit[y * width + x] = (frame[page + x] & bit) != 0;
            }
        }
        return lit;
    }

    /**
     * The commit checksum: XOR of every byte of the frame. The firmware compares
     * it on commit and reports it on an ImageControl read, which is how the phone
     * decides whether the watch already holds this image.
     */
    public static byte xor8(final byte[] frame) {
        byte acc = 0;
        for (final byte b : frame) {
            acc ^= b;
        }
        return acc;
    }
}
