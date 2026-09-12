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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which firmware first shipped which phone-facing feature, and a tolerant parser
 * for the watch's DIS firmware-revision string.
 *
 * The watch reports {@code "MAJOR.MINOR.PATCH"} plus an optional build suffix --
 * the bench image says {@code "3.0.0-bl"} (UART backdoor open, FW91
 * {@code Firmware/BUILD.md} 3.1). The suffix carries no feature information and
 * is ignored here.
 *
 * Every threshold below is the version whose CHANGELOG section introduced the
 * characteristic; a watch older than that simply does not have it, and writing
 * to it would be dropped by {@code TransactionBuilder} with a warning nobody
 * sees. The coordinator uses these to leave the corresponding settings out of
 * the device-settings screen, so the user is never offered a control the watch
 * cannot act on.
 *
 * Unknown is treated as capable: {@link #knownBelow} is only true when a version
 * was read AND parsed AND is older -- a device that has never connected shows
 * every setting rather than none.
 */
public final class F91KeplerFirmware {
    private F91KeplerFirmware() {
    }

    /** ModeOrder grew to the full ten screen ids (Bluetooth, Image) in 2.16.0. */
    public static final int[] MIN_MODE_ORDER_10 = {2, 16, 0};
    /** Image Service A3F0 (chunked 96x39 upload), 2.16.0. */
    public static final int[] MIN_IMAGE = {2, 16, 0};
    /** Alert Service D4F1 (timer expired / alarm fired notify), 2.23.0, issue #209. */
    public static final int[] MIN_ALERTS = {2, 23, 0};
    /** UI Config Brightness F2F2, 2.25.0, issue #211. */
    public static final int[] MIN_BRIGHTNESS = {2, 25, 0};
    /** Clock Service RadioSchedule B2F7 (radio-off windows), 2.26.0, issue #213. */
    public static final int[] MIN_RADIO_SCHEDULE = {2, 26, 0};

    private static final Pattern VERSION = Pattern.compile("^\\s*v?(\\d+)\\.(\\d+)\\.(\\d+)");

    /**
     * Parse {@code "3.0.0"}, {@code "3.0.0-bl"}, {@code "v2.31.7-bl"} into
     * {@code {major, minor, patch}}; {@code null} when there is no such prefix.
     */
    @Nullable
    public static int[] parse(@Nullable final String firmware) {
        if (firmware == null) {
            return null;
        }
        final Matcher m = VERSION.matcher(firmware);
        if (!m.find()) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
            };
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** Numeric (not lexical) comparison: 2.9.0 &lt; 2.16.0 &lt; 3.0.0. */
    public static int compare(final int[] a, final int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] < b[i] ? -1 : 1;
            }
        }
        return 0;
    }

    /**
     * True only when the firmware version is known, parseable, and older than
     * {@code min}. Null or unparseable -&gt; false, so nothing is hidden on a
     * device whose firmware has not been read yet.
     */
    public static boolean knownBelow(@Nullable final String firmware, final int[] min) {
        final int[] v = parse(firmware);
        return v != null && compare(v, min) < 0;
    }

    /** True when the version is known and at least {@code min}. */
    public static boolean atLeast(@Nullable final String firmware, final int[] min) {
        final int[] v = parse(firmware);
        return v != null && compare(v, min) >= 0;
    }
}
