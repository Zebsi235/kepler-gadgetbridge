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

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The second time zone of firmware 3.3.2 (Record ZONE 0x0E): the zones the
 * picker offers, the three-letter name the watch shows, and the record
 * payload. The watch has no zone database, so the phone sends the offset now,
 * the moment of the next change and the offset after it; it does so on every
 * connect, which also covers the change after that one.
 */
public final class F91KeplerZone {
    /** The watch takes offsets from UTC-12 to UTC+14, in seconds east. */
    static final int OFFSET_MIN = -43200;
    static final int OFFSET_MAX = 50400;
    static final int PAYLOAD_LEN = 15;
    /** How far ahead the next offset change is looked for (zones change at most twice a year). */
    static final long SCAN_MS = 400L * 24 * 60 * 60 * 1000;
    private static final long SCAN_STEP_MS = 60L * 60 * 1000;

    private static final String[] REGIONS = {
            "Africa/", "America/", "Antarctica/", "Asia/", "Atlantic/", "Australia/",
            "Europe/", "Indian/", "Pacific/"};

    /** Names people know better than the first letters of the city. */
    private static final Map<String, String> KNOWN = new HashMap<>();

    static {
        KNOWN.put("America/New_York", "NYC");
        KNOWN.put("America/Los_Angeles", "LAX");
        KNOWN.put("America/Chicago", "CHI");
        KNOWN.put("America/Denver", "DEN");
        KNOWN.put("America/Sao_Paulo", "SAO");
        KNOWN.put("America/Mexico_City", "MEX");
        KNOWN.put("America/Buenos_Aires", "BUE");
        KNOWN.put("America/Argentina/Buenos_Aires", "BUE");
        KNOWN.put("Europe/London", "LON");
        KNOWN.put("Europe/Zurich", "ZRH");
        KNOWN.put("Europe/Moscow", "MOW");
        KNOWN.put("Asia/Tokyo", "TYO");
        KNOWN.put("Asia/Hong_Kong", "HKG");
        KNOWN.put("Asia/Singapore", "SIN");
        KNOWN.put("Asia/Dubai", "DXB");
        KNOWN.put("Asia/Kolkata", "IND");
        KNOWN.put("Asia/Calcutta", "IND");
        KNOWN.put("Pacific/Auckland", "AKL");
        KNOWN.put("Pacific/Honolulu", "HNL");
    }

    private F91KeplerZone() {
    }

    /** Region/City zone ids for the picker, sorted (no Etc/ or legacy aliases). */
    public static List<String> pickerIds() {
        final List<String> ids = new ArrayList<>();
        for (final String id : TimeZone.getAvailableIDs()) {
            for (final String region : REGIONS) {
                if (id.startsWith(region)) {
                    ids.add(id);
                    break;
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /** "America/New York (UTC-04:00)": the zone as the picker lists it, offset as of now. */
    public static String label(final String zoneId, final long nowMillis) {
        final int minutes = TimeZone.getTimeZone(zoneId).getOffset(nowMillis) / 60000;
        final int abs = Math.abs(minutes);
        return String.format(Locale.ROOT, "%s (UTC%s%02d:%02d)", zoneId.replace('_', ' '),
                minutes < 0 ? "-" : "+", abs / 60, abs % 60);
    }

    /**
     * The three capitals the watch shows: the user's own name if it has three
     * letters (accents folded), else one made from the zone.
     */
    public static String name(@Nullable final String custom, final String zoneId) {
        final String own = letters(custom);
        return own.length() >= 3 ? own.substring(0, 3) : autoName(zoneId);
    }

    /** NYC for New York and a few more; else from the city: VIE(nna), BAI for Buenos Aires-like names. */
    public static String autoName(final String zoneId) {
        final String known = KNOWN.get(zoneId);
        if (known != null) {
            return known;
        }
        final String city = zoneId.substring(zoneId.lastIndexOf('/') + 1);
        final String[] words = city.split("[_\\-]+");
        final StringBuilder out = new StringBuilder();
        if (words.length >= 2) {
            // initials, then more letters of the last word: Port_of_Spain POS, Ho_Chi_Minh HCM
            for (int i = 0; i < words.length && out.length() < 3; i++) {
                final String w = letters(words[i]);
                if (!w.isEmpty()) {
                    out.append(w.charAt(0));
                }
            }
            final String last = letters(words[words.length - 1]);
            for (int i = 1; i < last.length() && out.length() < 3; i++) {
                out.append(last.charAt(i));
            }
        } else {
            out.append(letters(city));
        }
        while (out.length() < 3) {
            out.append('X');
        }
        return out.substring(0, 3);
    }

    /** Upper-case A-Z only, accents folded ("Zürich" gives ZURICH). */
    private static String letters(@Nullable final String s) {
        if (s == null) {
            return "";
        }
        final String folded = Normalizer.normalize(s, Normalizer.Form.NFD).toUpperCase(Locale.ROOT);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < folded.length(); i++) {
            final char c = folded.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** UTC millis of the next offset change after {@code nowMillis}, to the second; 0 if none within a year. */
    static long nextChange(final TimeZone tz, final long nowMillis) {
        final int now = tz.getOffset(nowMillis);
        for (long t = nowMillis; t < nowMillis + SCAN_MS; t += SCAN_STEP_MS) {
            final long next = t + SCAN_STEP_MS;
            if (tz.getOffset(next) != now) {
                long lo = t;
                long hi = next;
                while (hi - lo > 1000) {
                    final long mid = lo + (hi - lo) / 2;
                    if (tz.getOffset(mid) == now) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                // Zones change on a whole second, the only one in (lo, hi]
                return hi / 1000 * 1000;
            }
        }
        return 0;
    }

    /**
     * The 15-byte ZONE payload, or null when the zone is outside what the
     * watch takes (offset beyond UTC-12..UTC+14 or not whole minutes).
     */
    @Nullable
    public static byte[] payload(final TimeZone tz, final long nowMillis, final String name) {
        final int offsetNow = tz.getOffset(nowMillis) / 1000;
        final long change = nextChange(tz, nowMillis);
        final int offsetAfter = change == 0 ? offsetNow : tz.getOffset(change) / 1000;
        if (!fits(offsetNow) || !fits(offsetAfter) || name.length() != 3) {
            return null;
        }
        return ByteBuffer.allocate(PAYLOAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(offsetNow)
                .putInt((int) (change / 1000))
                .putInt(offsetAfter)
                .put(name.getBytes(StandardCharsets.US_ASCII))
                .array();
    }

    private static boolean fits(final int offset) {
        return offset >= OFFSET_MIN && offset <= OFFSET_MAX && offset % 60 == 0;
    }
}
