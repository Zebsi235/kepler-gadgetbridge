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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Wire-format encoders for the F91 Kepler GATT characteristics. The
 * CC2640R2F firmware uses memcpy onto native uint32/int16 fields, so byte
 * order on the wire is the MCU's native little-endian. JVM ByteBuffer
 * defaults to big-endian — LE must be set explicitly.
 */
public final class F91KeplerByteEncoder {

    private F91KeplerByteEncoder() {}

    /** uint32 little-endian Unix epoch in seconds (UTC). */
    public static byte[] timeEpoch(long epochSeconds) {
        if (epochSeconds < 0L || epochSeconds > 0xFFFFFFFFL) {
            throw new IllegalArgumentException(
                    "epoch seconds out of uint32 range: " + epochSeconds);
        }
        return ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) epochSeconds)
                .array();
    }

    /**
     * int16 little-endian seconds west of UTC. The firmware uses
     * {@code int16_t TimeZone} (signed); negative values represent east of
     * UTC (e.g. Berlin/CET = -3600). This is the corrected encoding —
     * earlier versions of the F91 Kepler companion app used unsigned
     * uint16 and could not represent eastern offsets.
     */
    public static byte[] timezoneSecondsWest(int secondsWest) {
        if (secondsWest < Short.MIN_VALUE || secondsWest > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "timezone seconds out of int16 range: " + secondsWest);
        }
        return ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) secondsWest)
                .array();
    }

    /** 0 = 12-hour, 1 = 24-hour. */
    public static byte[] timeMode(boolean twentyFourHour) {
        return new byte[] { twentyFourHour ? (byte) 0x01 : (byte) 0x00 };
    }

    /** 0 = DST off, 1 = DST on. */
    public static byte[] dst(boolean enabled) {
        return new byte[] { enabled ? (byte) 0x01 : (byte) 0x00 };
    }

    /** Single-byte notification-bar bitmask. */
    public static byte[] notificationBar(int bitmask) {
        return new byte[] { (byte) (bitmask & 0xFF) };
    }

    /**
     * Truncate a UTF-8 encoded contact name to at most
     * {@link F91KeplerConstants#CONTACT_NAME_MAX_BYTES} bytes without
     * splitting a multi-byte codepoint mid-sequence.
     */
    public static byte[] contactName(String raw) {
        if (raw == null) raw = "";
        byte[] full = raw.getBytes(StandardCharsets.UTF_8);
        if (full.length <= F91KeplerConstants.CONTACT_NAME_MAX_BYTES) {
            return full;
        }
        int cut = F91KeplerConstants.CONTACT_NAME_MAX_BYTES;
        // Walk backwards until we land on a UTF-8 start byte (a byte where
        // the top two bits are not 10xxxxxx, i.e. not a continuation byte).
        while (cut > 0 && (full[cut] & 0xC0) == 0x80) {
            cut--;
        }
        byte[] out = new byte[cut];
        System.arraycopy(full, 0, out, 0, cut);
        return out;
    }
}
