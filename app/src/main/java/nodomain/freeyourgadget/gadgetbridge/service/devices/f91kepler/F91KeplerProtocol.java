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
package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;

/**
 * Byte serializers for the F91 Kepler GATT characteristics. Ported 1:1 from
 * the proven companion-app serializers
 * ({@code Software/.../util/Endianness.kt}, {@code .../ble/WatchData.kt},
 * {@code .../domain/ContactNameFormatter.kt}). The CC2640R2F is little-endian
 * and the firmware memcpys straight onto its uint32/int16 fields, so the wire
 * order is little-endian.
 */
final class F91KeplerProtocol {
    private F91KeplerProtocol() {
    }

    /** Time characteristic: Unix epoch seconds as uint32 little-endian (4 bytes). */
    static byte[] time(final long epochSeconds) {
        return ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) (epochSeconds & 0xFFFFFFFFL)) // bit pattern preserved
                .array();
    }

    /**
     * TimeZone characteristic: signed seconds WEST of UTC as int16 little-endian
     * (2 bytes). East-of-UTC zones are negative (Berlin/CET = -3600). Clamped to
     * the int16 range, which covers every real offset except UTC+12/13/14.
     */
    static byte[] timezoneWest(final int secondsWest) {
        final int clamped = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, secondsWest));
        return ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) clamped)
                .array();
    }

    /** Time mode characteristic: 0x00 = 12-hour, 0x01 = 24-hour. */
    static byte[] timeMode(final boolean is24h) {
        return new byte[]{(byte) (is24h ? 0x01 : 0x00)};
    }

    /** DST characteristic: 0x00 = off, 0x01 = on. */
    static byte[] dst(final boolean enabled) {
        return new byte[]{(byte) (enabled ? 0x01 : 0x00)};
    }

    /**
     * Incoming Call / Incoming Text name: raw UTF-8, no length prefix, no null
     * terminator, truncated to {@link F91KeplerConstants#CONTACT_NAME_MAX_BYTES}
     * without splitting a multi-byte codepoint mid-sequence.
     */
    static byte[] contactName(final String name) {
        if (name == null) {
            return new byte[0];
        }
        return truncateUtf8(name, F91KeplerConstants.CONTACT_NAME_MAX_BYTES);
    }

    private static byte[] truncateUtf8(final String input, final int maxBytes) {
        if (maxBytes <= 0 || input.isEmpty()) {
            return new byte[0];
        }
        final byte[] full = input.getBytes(StandardCharsets.UTF_8);
        if (full.length <= maxBytes) {
            return full;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(maxBytes);
        int byteCount = 0;
        int i = 0;
        while (i < input.length()) {
            final int codePoint = input.codePointAt(i);
            final int charCount = Character.charCount(codePoint);
            final byte[] cpBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            if (byteCount + cpBytes.length > maxBytes) {
                break;
            }
            out.write(cpBytes, 0, cpBytes.length);
            byteCount += cpBytes.length;
            i += charCount;
        }
        return out.toByteArray();
    }
}
