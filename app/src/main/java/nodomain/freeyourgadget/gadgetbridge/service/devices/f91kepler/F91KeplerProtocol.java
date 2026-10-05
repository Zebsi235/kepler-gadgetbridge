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
import java.text.Normalizer;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

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

    /** App label is capped first so the sender keeps the rest of the budget;
     *  ~8 glyphs is what fits the popup's tile-side text column. */
    private static final int POPUP_APP_MAX = 8;

    /**
     * Incoming Text popup (A2F3): {@code [appLen][app UTF-8][sender UTF-8]} so the
     * watch's split-tile popup can show the originating app label above the
     * sender name. The whole payload must fit the firmware's
     * {@link F91KeplerConstants#CONTACT_NAME_MAX_BYTES}-byte characteristic, so
     * the app is capped first and the sender takes the remainder. Backward
     * compatible on the watch: a real appLen is small (&lt; 0x20), so a legacy
     * bare-name write is still recognised and rendered without an app label.
     */
    static byte[] incomingTextPopup(final String app, final String sender) {
        final byte[] appB = truncateUtf8(app == null ? "" : app, POPUP_APP_MAX);
        final int senderBudget = F91KeplerConstants.CONTACT_NAME_MAX_BYTES - 1 - appB.length;
        final byte[] senB = truncateUtf8(sender == null ? "" : sender, Math.max(0, senderBudget));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(appB.length & 0xFF);
        out.write(appB, 0, appB.length);
        out.write(senB, 0, senB.length);
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

    /**
     * Decode an AlertEvent notification byte (Alert Service, D4F1) into the
     * find-phone event that actually raises the alert on this phone. Issue #209.
     *
     * <p>{@code vibrateOnly} selects between the two ways of getting the user's
     * attention: START rings, START_VIBRATE only vibrates. Both are existing
     * upstream behaviours, so no new event type is needed.
     *
     * <p>Returns {@link GBDeviceEventFindPhone.Event#UNKNOWN} for an unknown
     * byte, so a future firmware event cannot make an old app ring for something
     * it does not understand.
     */
    static GBDeviceEventFindPhone.Event alertEvent(final byte ev, final boolean vibrateOnly) {
        switch (ev) {
            case F91KeplerConstants.ALERT_EVENT_TIMER:
            case F91KeplerConstants.ALERT_EVENT_ALARM:
                return vibrateOnly ? GBDeviceEventFindPhone.Event.START_VIBRATE
                                   : GBDeviceEventFindPhone.Event.START;
            default:
                return GBDeviceEventFindPhone.Event.UNKNOWN;
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
     * Brightness characteristic (UI Config, F2F2): one step index (issue #211).
     * The watch rejects a step outside its ladder with an ATT error, so an
     * out-of-range preference is clamped here rather than sent and refused --
     * a stale pref from a future build must not leave the user unable to change
     * brightness at all.
     */
    static byte[] brightness(final int step) {
        int s = step;
        if (s < 0) {
            s = 0;
        } else if (s > F91KeplerConstants.BRIGHTNESS_STEPS - 1) {
            s = F91KeplerConstants.BRIGHTNESS_STEPS - 1;
        }
        return new byte[]{(byte) s};
    }

    /**
     * Radio schedule (B2F7, issue #213): {@code [enabled][start u16 LE][end u16 LE]}
     * in LOCAL minutes since midnight.
     *
     * A zero-length window (start == end) is sent as DISABLED rather than as a
     * window: the firmware refuses it, and silently turning "no window" into a
     * permanent radio-off would be far worse than ignoring a misconfiguration.
     * Out-of-range minutes are clamped into the day for the same reason
     * brightness clamps -- a value the watch will refuse is not worth sending.
     */
    static byte[] radioSchedule(final boolean enabled, final int startMin, final int endMin) {
        final int start = Math.max(0, Math.min(startMin, 1439));
        final int end = Math.max(0, Math.min(endMin, 1439));
        final boolean on = enabled && start != end;
        return new byte[]{
                (byte) (on ? 1 : 0),
                (byte) (start & 0xFF), (byte) ((start >> 8) & 0xFF),
                (byte) (end & 0xFF), (byte) ((end >> 8) & 0xFF),
        };
    }

    /**
     * Minutes since midnight from an {@code "HH:mm"} preference (what
     * XTimePreference stores). Returns {@code def} for anything unparseable, so a
     * corrupted preference cannot silence the radio at an arbitrary hour.
     */
    static int minutesFromHhMm(final String hhmm, final int def) {
        if (hhmm == null) {
            return def;
        }
        final String[] parts = hhmm.split(":");
        if (parts.length != 2) {
            return def;
        }
        try {
            final int h = Integer.parseInt(parts[0].trim());
            final int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return def;
            }
            return h * 60 + m;
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    // --- Image Service (A3F0) ----------------------------------------------

    /** ImageControl write that arms a transfer and invalidates the current image. */
    static byte[] imageBegin() {
        return new byte[]{F91KeplerConstants.IMAGE_CTRL_BEGIN};
    }

    /**
     * ImageControl write that latches the staged frame: {@code [0x02][xor8]}. The
     * firmware recomputes the checksum over its 480-byte buffer and rejects the
     * write on a mismatch, which is how a mis-ordered or short upload is caught.
     */
    static byte[] imageCommit(final byte xor8) {
        return new byte[]{F91KeplerConstants.IMAGE_CTRL_COMMIT, xor8};
    }

    /**
     * Split a 480-byte frame into the 26 ImageChunk writes: {@code [seq][data]},
     * where chunk {@code seq} carries the 19 bytes at offset {@code seq * 19} and
     * the last chunk carries the remaining 5.
     */
    static byte[][] imageChunks(final byte[] frame) {
        if (frame == null || frame.length != F91KeplerConstants.IMAGE_BYTES) {
            throw new IllegalArgumentException("expected " + F91KeplerConstants.IMAGE_BYTES
                    + " bytes, got " + (frame == null ? -1 : frame.length));
        }
        final byte[][] chunks = new byte[F91KeplerConstants.IMAGE_CHUNK_COUNT][];
        for (int seq = 0; seq < chunks.length; seq++) {
            final int offset = seq * F91KeplerConstants.IMAGE_CHUNK_DATA;
            final int length = Math.min(F91KeplerConstants.IMAGE_CHUNK_DATA, frame.length - offset);
            final byte[] write = new byte[1 + length];
            write[0] = (byte) seq;
            System.arraycopy(frame, offset, write, 1, length);
            chunks[seq] = write;
        }
        return chunks;
    }

    /**
     * True when an ImageControl read ({@code [valid][xor8]}) says the watch is
     * holding exactly the frame with this checksum — i.e. there is nothing to
     * upload. Any short, absent or invalid response counts as a mismatch.
     */
    static boolean imageControlMatches(final byte[] value, final byte xor8) {
        return value != null && value.length >= 2 && value[0] == 1 && value[1] == xor8;
    }

    // --- Firmware 3.1 ---------------------------------------------------------

    /** UiOptions (F2F3): the bitmask as uint16 little-endian. */
    static byte[] uiOptions(final int bits) {
        return new byte[]{(byte) (bits & 0xFF), (byte) ((bits >> 8) & 0xFF)};
    }

    /**
     * UiOptions bits from the settings. {@code lang} is the weekday-language
     * preference: "de", "en", or "auto" -- German when the phone's language is.
     * Only the four known bits are ever produced; the watch refuses any other.
     */
    static int uiOptionBits(final boolean weekday, final String lang, final Locale locale,
                            final boolean quietText, final boolean hourlyChime) {
        int bits = 0;
        if (weekday) {
            bits |= F91KeplerConstants.UIOPT_WEEKDAY;
        }
        final boolean german = "de".equals(lang)
                || (!"en".equals(lang) && locale != null && "de".equals(locale.getLanguage()));
        if (german) {
            bits |= F91KeplerConstants.UIOPT_WEEKDAY_DE;
        }
        if (quietText) {
            bits |= F91KeplerConstants.UIOPT_QUIET_TEXT;
        }
        if (hourlyChime) {
            bits |= F91KeplerConstants.UIOPT_HOURLY_CHIME;
        }
        return bits;
    }

    /** Record (F2F4) frame: {@code [type][index][payload]}. */
    static byte[] record(final byte type, final int index, final byte[] payload) {
        final byte[] out = new byte[2 + payload.length];
        out[0] = type;
        out[1] = (byte) index;
        System.arraycopy(payload, 0, out, 2, payload.length);
        return out;
    }

    /**
     * Alarm slot payload {@code [enabled][hh][mm][daymask]}, local time. The
     * daymask is Gadgetbridge's own repetition mask -- Alarm.ALARM_MON = 1 ..
     * ALARM_SUN = 64 is bit-for-bit the firmware's Monday = bit 0 -- and
     * ALARM_ONCE (0) is the firmware's one-shot, which turns itself off as it
     * fires. Out-of-range times are sent disabled at 00:00 rather than refused.
     */
    static byte[] alarmSlot(final boolean enabled, final int hh, final int mm, final int daymask) {
        final boolean valid = hh >= 0 && hh < 24 && mm >= 0 && mm < 60;
        return new byte[]{
                (byte) (enabled && valid ? 1 : 0),
                (byte) (valid ? hh : 0),
                (byte) (valid ? mm : 0),
                (byte) (daymask & 0x7F),
        };
    }

    /**
     * What the watch can show of a counter name: printable ASCII only (the
     * watch refuses anything else), accents and umlauts transliterated rather
     * than dropped, at most {@link F91KeplerConstants#COUNTER_NAME_MAX} chars.
     */
    static String sanitizeCounterName(final String name) {
        if (name == null) {
            return "";
        }
        final String t = name.trim()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                .replace("ß", "ss");
        final String plain = Normalizer.normalize(t, Normalizer.Form.NFD);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < plain.length() && out.length() < F91KeplerConstants.COUNTER_NAME_MAX; i++) {
            final char c = plain.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    /**
     * Counter payload: KEEP the count (0xFFFF) and set the name. The count is
     * the wearer's -- it is tapped on the watch -- so the phone never writes
     * one. An empty name removes the counter (its page is then skipped).
     */
    static byte[] counterName(final String name) {
        final byte[] n = sanitizeCounterName(name).getBytes(StandardCharsets.US_ASCII);
        final byte[] out = new byte[2 + n.length];
        out[0] = (byte) (F91KeplerConstants.COUNTER_KEEP_VALUE & 0xFF);
        out[1] = (byte) ((F91KeplerConstants.COUNTER_KEEP_VALUE >> 8) & 0xFF);
        System.arraycopy(n, 0, out, 2, n.length);
        return out;
    }

    /** Forecast day payload {@code [condition 0..7][high i8][low i8]}, clamped. */
    static byte[] forecastDay(final int cond, final int high, final int low) {
        return new byte[]{
                weatherCondition(cond)[0],
                weatherTemperature(high)[0],
                weatherTemperature(low)[0],
        };
    }

    /** Kelvin (Gadgetbridge's weather unit) to the integer the watch shows. */
    static int tempInUnit(final double kelvin, final boolean fahrenheit) {
        final int celsius = (int) Math.round(kelvin - 273.15);
        return fahrenheit ? (int) Math.round(celsius * 9.0 / 5.0 + 32.0) : celsius;
    }

    /** Sun payload {@code [rise u16 LE][set u16 LE]}, local minutes or 0xFFFF. */
    static byte[] sunTimes(final int riseMin, final int setMin) {
        return new byte[]{
                (byte) (riseMin & 0xFF), (byte) ((riseMin >> 8) & 0xFF),
                (byte) (setMin & 0xFF), (byte) ((setMin >> 8) & 0xFF),
        };
    }

    /**
     * Local minute of day of {@code epochSeconds} in {@code tz}, or
     * {@link F91KeplerConstants#SUN_NONE} when the event is absent (0) or does
     * not fall on {@code today}'s local date -- polar day or night, where
     * weather providers report the next event days away.
     */
    static int sunMinute(final long epochSeconds, final Calendar today, final TimeZone tz) {
        if (epochSeconds <= 0) {
            return F91KeplerConstants.SUN_NONE;
        }
        final Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(epochSeconds * 1000L);
        final Calendar d = (Calendar) today.clone();
        d.setTimeZone(tz);
        if (c.get(Calendar.YEAR) != d.get(Calendar.YEAR)
                || c.get(Calendar.DAY_OF_YEAR) != d.get(Calendar.DAY_OF_YEAR)) {
            return F91KeplerConstants.SUN_NONE;
        }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }
}
