/*  Copyright (C) 2026 F91 Kepler contributors

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
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Pins the F91 Kepler GATT wire format. Each assertion ties to a value
 * the firmware reads via {@code memcpy} on a CC2640R2F, so a regression
 * here would silently break interop with the watch.
 */
public class F91KeplerByteEncoderTest {

    // ---- Time --------------------------------------------------------

    @Test
    public void timeEpoch_zeroSerialisesToFourZeros() {
        assertArrayEquals(new byte[]{0, 0, 0, 0}, F91KeplerByteEncoder.timeEpoch(0L));
    }

    @Test
    public void timeEpoch_smallValueIsLittleEndian() {
        // 0x12345678 → bytes 78 56 34 12 little-endian
        assertArrayEquals(
                new byte[]{(byte) 0x78, (byte) 0x56, (byte) 0x34, (byte) 0x12},
                F91KeplerByteEncoder.timeEpoch(0x12345678L));
    }

    @Test
    public void timeEpoch_maxUint32() {
        assertArrayEquals(
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                F91KeplerByteEncoder.timeEpoch(0xFFFFFFFFL));
    }

    @Test
    public void timeEpoch_negativeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> F91KeplerByteEncoder.timeEpoch(-1L));
    }

    @Test
    public void timeEpoch_overflowRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> F91KeplerByteEncoder.timeEpoch(0x1_0000_0000L));
    }

    // ---- TimeZone (signed int16) ------------------------------------

    @Test
    public void timezone_zero() {
        assertArrayEquals(new byte[]{0, 0}, F91KeplerByteEncoder.timezoneSecondsWest(0));
    }

    @Test
    public void timezone_berlinIsNegative() {
        // Berlin/CET = -3600 (east of UTC). Two's complement int16: 0xF1F0.
        // Little-endian bytes: low first → F0, F1.
        assertArrayEquals(
                new byte[]{(byte) 0xF0, (byte) 0xF1},
                F91KeplerByteEncoder.timezoneSecondsWest(-3600));
    }

    @Test
    public void timezone_pacificIsPositive() {
        // PST = +28800 = 0x7080. Little-endian bytes: 80, 70.
        assertArrayEquals(
                new byte[]{(byte) 0x80, (byte) 0x70},
                F91KeplerByteEncoder.timezoneSecondsWest(28800));
    }

    @Test
    public void timezone_int16BoundsAccepted() {
        assertArrayEquals(new byte[]{(byte) 0x00, (byte) 0x80},
                F91KeplerByteEncoder.timezoneSecondsWest(Short.MIN_VALUE));
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0x7F},
                F91KeplerByteEncoder.timezoneSecondsWest(Short.MAX_VALUE));
    }

    @Test
    public void timezone_outOfRangeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> F91KeplerByteEncoder.timezoneSecondsWest(Short.MIN_VALUE - 1));
        assertThrows(IllegalArgumentException.class,
                () -> F91KeplerByteEncoder.timezoneSecondsWest(Short.MAX_VALUE + 1));
    }

    // ---- Single-byte enums ------------------------------------------

    @Test
    public void timeMode_isOneByte() {
        assertArrayEquals(new byte[]{0x00}, F91KeplerByteEncoder.timeMode(false));
        assertArrayEquals(new byte[]{0x01}, F91KeplerByteEncoder.timeMode(true));
    }

    @Test
    public void dst_isOneByte() {
        assertArrayEquals(new byte[]{0x00}, F91KeplerByteEncoder.dst(false));
        assertArrayEquals(new byte[]{0x01}, F91KeplerByteEncoder.dst(true));
    }

    @Test
    public void notificationBar_masksToLowByte() {
        assertArrayEquals(new byte[]{0x00}, F91KeplerByteEncoder.notificationBar(0));
        assertArrayEquals(new byte[]{0x0F}, F91KeplerByteEncoder.notificationBar(0x0F));
        // High bits are masked off — the firmware only reads bits 0..3.
        assertArrayEquals(new byte[]{0x05}, F91KeplerByteEncoder.notificationBar(0xFF05));
    }

    // ---- Contact name (UTF-8 with codepoint-safe truncation) --------

    @Test
    public void contactName_nullAndEmptyAreEmpty() {
        assertEquals(0, F91KeplerByteEncoder.contactName(null).length);
        assertEquals(0, F91KeplerByteEncoder.contactName("").length);
    }

    @Test
    public void contactName_shortAsciiUnchanged() {
        assertArrayEquals("John".getBytes(StandardCharsets.UTF_8),
                F91KeplerByteEncoder.contactName("John"));
    }

    @Test
    public void contactName_exactlyTwentyAsciiUnchanged() {
        String twenty = "12345678901234567890";  // 20 chars, 20 bytes
        assertEquals(20, F91KeplerByteEncoder.contactName(twenty).length);
        assertArrayEquals(twenty.getBytes(StandardCharsets.UTF_8),
                F91KeplerByteEncoder.contactName(twenty));
    }

    @Test
    public void contactName_longAsciiTruncatedAtTwenty() {
        byte[] out = F91KeplerByteEncoder.contactName("This is way too long to fit");
        assertEquals(20, out.length);
        assertEquals("This is way too long", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    public void contactName_doesNotSplitMultibyteCodepoint() {
        // Each rocket emoji is 4 UTF-8 bytes. 6 × 🚀 = 24 bytes. Byte
        // index 20 lands exactly on the start of the 6th rocket (a 4-byte
        // sequence-start byte, not a continuation byte) — so the cut is
        // already on a codepoint boundary and 5 complete rockets fit in
        // the 20-byte limit unchanged.
        byte[] out = F91KeplerByteEncoder.contactName("🚀🚀🚀🚀🚀🚀");
        assertEquals(20, out.length);
        assertEquals("🚀🚀🚀🚀🚀", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    public void contactName_walksBackOverContinuationBytes() {
        // 4 ASCII + 6 × 🚀 = 4 + 24 = 28 bytes. Byte 20 is mid-sequence
        // of the 5th rocket (byte 4 of bytes 16..19 covers ★4, byte 20
        // begins ★5). Walk-back must move cut from inside any partial
        // codepoint back to the prior boundary. Here byte 20 is a fresh
        // start byte (F0), so no walk-back is needed — 4 ASCII + 4 emoji
        // = 4 + 16 = 20 fits.
        byte[] out = F91KeplerByteEncoder.contactName("Hi: 🚀🚀🚀🚀🚀🚀");
        assertEquals(20, out.length);
        assertEquals("Hi: 🚀🚀🚀🚀", new String(out, StandardCharsets.UTF_8));

        // 3 ASCII + 6 × 🚀 = 3 + 24 = 27 bytes. Byte 20 is inside the 5th
        // rocket — specifically the last byte of its 4-byte sequence
        // (bytes 16, 17, 18, 19 are the 5th emoji; byte 20 begins the 6th).
        // Wait — that means no walk-back either. Let's force a real
        // walk-back: 1 ASCII + 6 × 🚀 = 1 + 24 = 25 bytes. Byte 20 lands
        // 2 bytes into the 5th emoji (a continuation byte, 0x9A) and the
        // walker must step back to the 5th emoji's start (byte 17), which
        // is itself the 4-byte sequence-start that's *also* in the
        // truncated region — actually the boundary is byte 17, so 17
        // bytes returned = 1 ASCII + 4 emoji.
        byte[] withWalk = F91KeplerByteEncoder.contactName("x🚀🚀🚀🚀🚀🚀");
        assertEquals(17, withWalk.length);
        assertEquals("x🚀🚀🚀🚀", new String(withWalk, StandardCharsets.UTF_8));
    }

    @Test
    public void contactName_keepsAsciiThenDropsPartialEmoji() {
        // "Jacques 🚀🚀🚀" = 8 ASCII + 12 emoji bytes = 20 total — fits.
        byte[] fits = F91KeplerByteEncoder.contactName("Jacques 🚀🚀🚀");
        assertEquals(20, fits.length);
        // "Jacques 🚀🚀🚀🚀" = 8 + 16 = 24 — must drop the trailing 🚀.
        byte[] truncated = F91KeplerByteEncoder.contactName("Jacques 🚀🚀🚀🚀");
        assertEquals(20, truncated.length);
        assertEquals("Jacques 🚀🚀🚀", new String(truncated, StandardCharsets.UTF_8));
    }
}
