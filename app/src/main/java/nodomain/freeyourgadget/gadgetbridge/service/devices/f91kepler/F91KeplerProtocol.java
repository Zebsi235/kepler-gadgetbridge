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

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
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
     * Alarm time characteristic: absolute next-fire Unix epoch seconds as uint32
     * little-endian (4 bytes); 0 = disabled. Same wire format as {@link #time},
     * and compared against the watch's UTC clock, so this must be a UTC epoch.
     */
    static byte[] alarmTime(final long epochSeconds) {
        return ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) (epochSeconds & 0xFFFFFFFFL))
                .array();
    }

    /** Alarm enabled characteristic: 0x00 = off, 0x01 = on. */
    static byte[] alarmEnabled(final boolean enabled) {
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

    /**
     * Weather Temperature characteristic: a single signed byte, already in the
     * user's display unit (Gadgetbridge converts C/F; the watch shows a bare
     * integer + degree mark). Clamped to the int8 range.
     */
    static byte[] weatherTemperature(final int tempInUnit) {
        final int clamped = Math.max(-128, Math.min(127, tempInUnit));
        return new byte[]{(byte) clamped};
    }

    /** Weather Condition characteristic: a single byte 0..7 (clamped). */
    static byte[] weatherCondition(final int cond) {
        final int clamped = Math.max(0, Math.min(7, cond));
        return new byte[]{(byte) clamped};
    }

    /**
     * Map an OpenWeatherMap condition code (group by hundreds) to the watch's
     * 0..7 condition enum (see F91KeplerConstants.WX_*).
     */
    static int owmToCondition(final int owm) {
        switch (owm / 100) {
            case 2:  return F91KeplerConstants.WX_STORM;       // 2xx thunderstorm
            case 3:  return F91KeplerConstants.WX_RAIN;        // 3xx drizzle
            case 5:  return (owm <= 501) ? F91KeplerConstants.WX_RAIN
                                         : F91KeplerConstants.WX_HEAVY_RAIN; // 5xx rain
            case 6:  return F91KeplerConstants.WX_SNOW;        // 6xx snow
            case 7:  return F91KeplerConstants.WX_FOG;         // 7xx atmosphere (mist/fog/haze)
            case 8:
                if (owm == 800) return F91KeplerConstants.WX_SUN;            // clear
                if (owm == 801 || owm == 802) return F91KeplerConstants.WX_HALF_SUN; // few/scattered
                return F91KeplerConstants.WX_CLOUD;            // broken/overcast
            default: return F91KeplerConstants.WX_CLOUD;
        }
    }

    /**
     * Decode a PlaybackCmd notification byte (Music Control service, D2F1) into a
     * media-control event. Returns {@link GBDeviceEventMusicControl.Event#UNKNOWN}
     * for any unrecognized value so the caller can ignore it.
     */
    static GBDeviceEventMusicControl.Event musicCommand(final byte cmd) {
        switch (cmd) {
            case F91KeplerConstants.MUSIC_CMD_PLAY_PAUSE: return GBDeviceEventMusicControl.Event.PLAYPAUSE;
            case F91KeplerConstants.MUSIC_CMD_NEXT:       return GBDeviceEventMusicControl.Event.NEXT;
            case F91KeplerConstants.MUSIC_CMD_PREV:       return GBDeviceEventMusicControl.Event.PREVIOUS;
            default:                                      return GBDeviceEventMusicControl.Event.UNKNOWN;
        }
    }

    /**
     * Decode a FindPhoneCmd notification byte (Find Phone service, D3F1) into a
     * find-phone event (0 ring -> START, 1 stop -> STOP). Returns
     * {@link GBDeviceEventFindPhone.Event#UNKNOWN} for any unrecognized value.
     */
    static GBDeviceEventFindPhone.Event findPhoneCommand(final byte cmd) {
        switch (cmd) {
            case F91KeplerConstants.FIND_PHONE_CMD_RING: return GBDeviceEventFindPhone.Event.START;
            case F91KeplerConstants.FIND_PHONE_CMD_STOP: return GBDeviceEventFindPhone.Event.STOP;
            default:                                     return GBDeviceEventFindPhone.Event.UNKNOWN;
        }
    }

    /** Displayed widths the watch truncates to; we pre-truncate to keep the
     *  write within the firmware's NotificationEntry max (3 + app + sender). */
    private static final int NOTIF_APP_MAX = 11;
    private static final int NOTIF_SENDER_MAX = 20;

    /**
     * NotificationEntry characteristic (A2F4): one history slot as
     * {@code [slot][total][appLen][app UTF-8][sender UTF-8]}. app/sender are
     * truncated (UTF-8 safe). total = how many entries are currently active
     * (the watch shows that many; total 0 clears the list).
     */
    static byte[] notificationEntry(final int slot, final int total,
                                    final String app, final String sender) {
        final byte[] appB = truncateUtf8(app == null ? "" : app, NOTIF_APP_MAX);
        final byte[] senB = truncateUtf8(sender == null ? "" : sender, NOTIF_SENDER_MAX);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(slot & 0xFF);
        out.write(total & 0xFF);
        out.write(appB.length & 0xFF);
        out.write(appB, 0, appB.length);
        out.write(senB, 0, senB.length);
        return out.toByteArray();
    }

    /**
     * ModeOrder characteristic (UI Config, F2F1): Main (always first) followed by
     * the optional modes, ordered by their configured position (1..5); a position
     * &lt;= 0 means the mode is off (omitted). Ties are broken by canonical id so
     * the result is deterministic. Positions are given in canonical order
     * (Notifications, Timer, Music, Stopwatch, Info).
     */
    static byte[] modeOrder(final int posNotif, final int posTimer, final int posMusic,
                            final int posStopwatch, final int posInfo) {
        final byte[] ids = { F91KeplerConstants.MODE_NOTIF, F91KeplerConstants.MODE_TIMER,
                             F91KeplerConstants.MODE_MUSIC, F91KeplerConstants.MODE_STOPWATCH,
                             F91KeplerConstants.MODE_INFO };
        final int[] pos = { posNotif, posTimer, posMusic, posStopwatch, posInfo };
        final boolean[] used = new boolean[ids.length];

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(F91KeplerConstants.MODE_MAIN);
        for (int k = 0; k < ids.length; k++) {
            int best = -1;
            for (int i = 0; i < ids.length; i++) {
                if (used[i] || pos[i] <= 0) continue;
                if (best == -1 || pos[i] < pos[best] || (pos[i] == pos[best] && i < best)) {
                    best = i;
                }
            }
            if (best == -1) break;
            used[best] = true;
            out.write(ids[best]);
        }
        return out.toByteArray();
    }
}
