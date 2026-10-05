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

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The watch's mode cycle as the wearer edits it: one ordered list of the modes
 * they want, ticked and dragged into order in a single "Watch modes" list
 * (Gadgetbridge's DragSortListPreference). It replaced one position dropdown
 * per mode (thirteen of them on firmware 3.1), which the owner found hard to
 * use and which could express an over-full cycle without saying so.
 *
 * <p>Two stored lists, shown one at a time by the firmware gate:
 * {@link #PREF_MODES} holds the nine 3.0 modes, {@link #PREF_MODES_31} all
 * thirteen. Two lists rather than one because the list preference cannot show
 * a stored item that is not among its entries: a 3.0 watch must never be
 * offered the 3.1 screens, and a watch flashed back to 3.0 must not lose the
 * 3.1 choices either.
 *
 * <p>The value is the preference's own format: the ticked values, comma-
 * separated, in cycle order. The clock face is not in the list; it is always
 * first on the watch.
 *
 * <p>Pure apart from SharedPreferences, so it is unit-tested on the JVM.
 */
public final class F91KeplerModes {
    private F91KeplerModes() {
    }

    /** Ordered list of the nine 3.0 modes (fw >= 2.16.0, before 3.1). */
    public static final String PREF_MODES = "f91_modes";
    /** Ordered list of all thirteen modes (fw 3.1+). */
    public static final String PREF_MODES_31 = "f91_modes_31";

    /** Values, in canonical (screen id) order; index + 1 = the firmware's id. */
    public static final String[] VALUES = {
            "notif", "timer", "music", "stopwatch", "info", "flashlight",
            "findphone", "ble", "image",
            "weather", "counter0", "counter1", "counter2",
    };
    /** The first this many values exist on every supported firmware. */
    public static final int BASE_COUNT = 9;

    /** The pre-3.1.3 cycle: Main + 9. From fw 3.1.3 (FW91 #261) all 13 fit. */
    public static final int MAX_OPTIONAL_OLD = 9;
    public static final int MAX_OPTIONAL_ALL = VALUES.length;

    /** The legacy per-mode position keys, same order as {@link #VALUES}. */
    static final String[] LEGACY_KEYS = {
            F91KeplerConstants.PREF_MODE_POS_NOTIF, F91KeplerConstants.PREF_MODE_POS_TIMER,
            F91KeplerConstants.PREF_MODE_POS_MUSIC, F91KeplerConstants.PREF_MODE_POS_STOPWATCH,
            F91KeplerConstants.PREF_MODE_POS_INFO, F91KeplerConstants.PREF_MODE_POS_FLASHLIGHT,
            F91KeplerConstants.PREF_MODE_POS_FINDPHONE, F91KeplerConstants.PREF_MODE_POS_BLE,
            F91KeplerConstants.PREF_MODE_POS_IMAGE,
            F91KeplerConstants.PREF_MODE_POS_WEATHER, F91KeplerConstants.PREF_MODE_POS_COUNTER0,
            F91KeplerConstants.PREF_MODE_POS_COUNTER1, F91KeplerConstants.PREF_MODE_POS_COUNTER2,
    };

    /** The default cycle: the nine 3.0 modes in id order (as the watch ships). */
    public static List<String> defaultOrder() {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < BASE_COUNT; i++) {
            out.add(VALUES[i]);
        }
        return out;
    }

    /** Firmware id (1..13) of a value, or -1. */
    public static int idOf(final String value) {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i].equals(value)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Parse the stored list; unknown values and duplicates are dropped. */
    public static List<String> parse(@Nullable final String csv) {
        final Set<String> seen = new LinkedHashSet<>();
        if (csv != null && !csv.isEmpty()) {
            for (final String v : csv.split(",")) {
                final String t = v.trim();
                if (idOf(t) > 0) {
                    seen.add(t);
                }
            }
        }
        return new ArrayList<>(seen);
    }

    public static String join(final List<String> order) {
        final StringBuilder b = new StringBuilder();
        for (final String v : order) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(v);
        }
        return b.toString();
    }

    /**
     * The order the old per-mode position dropdowns described: modes with a
     * position 1..9 sorted by it, ties by id ("0" = off). Defaults are the old
     * XML defaults: the nine 3.0 modes at 1..9, the 3.1 screens off.
     */
    public static List<String> fromLegacyPositions(final SharedPreferences prefs, final int count) {
        final List<Integer> idx = new ArrayList<>();
        final int[] pos = new int[count];
        for (int i = 0; i < count; i++) {
            pos[i] = readPos(prefs, LEGACY_KEYS[i], i < BASE_COUNT ? i + 1 : 0);
            if (pos[i] > 0) {
                idx.add(i);
            }
        }
        Collections.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(final Integer a, final Integer b) {
                return pos[a] != pos[b] ? Integer.compare(pos[a], pos[b]) : Integer.compare(a, b);
            }
        });
        final List<String> out = new ArrayList<>();
        for (final int i : idx) {
            out.add(VALUES[i]);
        }
        return out;
    }

    private static boolean anyLegacy(final SharedPreferences prefs) {
        for (final String k : LEGACY_KEYS) {
            if (prefs.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static int readPos(final SharedPreferences prefs, final String key, final int def) {
        try {
            return Integer.parseInt(prefs.getString(key, Integer.toString(def)));
        } catch (final NumberFormatException | ClassCastException e) {
            return def;
        }
    }

    /**
     * Seed the two lists once, before the settings screen inflates them (the
     * list preference would otherwise persist its default over a custom order).
     * From the old position dropdowns if the wearer ever set any; the 3.1 list
     * otherwise starts from the 3.0 list, so an upgraded watch keeps its cycle.
     * Never overwrites a list that exists. Idempotent.
     */
    public static void migrate(final SharedPreferences prefs) {
        final SharedPreferences.Editor e = prefs.edit();
        boolean changed = false;
        final boolean legacy = anyLegacy(prefs);
        if (!prefs.contains(PREF_MODES)) {
            final List<String> base = legacy ? fromLegacyPositions(prefs, BASE_COUNT) : defaultOrder();
            e.putString(PREF_MODES, join(base));
            changed = true;
        }
        if (!prefs.contains(PREF_MODES_31)) {
            final List<String> all;
            if (legacy) {
                all = fromLegacyPositions(prefs, VALUES.length);
            } else if (prefs.contains(PREF_MODES)) {
                all = parse(prefs.getString(PREF_MODES, ""));
            } else {
                all = defaultOrder();
            }
            e.putString(PREF_MODES_31, join(all));
            changed = true;
        }
        if (changed) {
            e.apply();
        }
    }

    /** The list that applies to this firmware. */
    public static List<String> order(final SharedPreferences prefs, final boolean has31) {
        final String key = has31 ? PREF_MODES_31 : PREF_MODES;
        if (!prefs.contains(key)) {
            return has31 ? parse(prefs.getString(PREF_MODES, join(defaultOrder()))) : defaultOrder();
        }
        return parse(prefs.getString(key, ""));
    }

    /** How many optional modes this firmware cycles (Main not counted). */
    public static int maxOptional(@Nullable final String firmware) {
        return F91KeplerFirmware.atLeast(firmware, F91KeplerFirmware.MIN_MODE_ORDER_14)
                ? MAX_OPTIONAL_ALL : MAX_OPTIONAL_OLD;
    }

    /**
     * The ModeOrder bytes: Main (0) first, then the wanted modes this firmware
     * has, in list order, at most {@code maxOptional}. A 3.1 id is never sent
     * to a pre-3.1 watch (it refuses the whole order over one unknown id).
     */
    public static byte[] wireOrder(final List<String> order, final boolean has31, final int maxOptional) {
        final List<Byte> ids = kept(order, has31, maxOptional);
        final byte[] out = new byte[ids.size() + 1];
        out[0] = F91KeplerConstants.MODE_MAIN;
        for (int i = 0; i < ids.size(); i++) {
            out[i + 1] = ids.get(i);
        }
        return out;
    }

    /** Wanted modes that do not fit this firmware's cycle (for the toast). */
    public static int droppedCount(final List<String> order, final boolean has31, final int maxOptional) {
        int supported = 0;
        for (final String v : order) {
            final int id = idOf(v);
            if (id > 0 && (has31 || id <= BASE_COUNT)) {
                supported++;
            }
        }
        return Math.max(0, supported - maxOptional);
    }

    private static List<Byte> kept(final List<String> order, final boolean has31, final int maxOptional) {
        final List<Byte> ids = new ArrayList<>();
        for (final String v : order) {
            final int id = idOf(v);
            if (id <= 0 || (!has31 && id > BASE_COUNT) || ids.contains((byte) id)) {
                continue;
            }
            if (ids.size() >= maxOptional) {
                break;
            }
            ids.add((byte) id);
        }
        return ids;
    }
}
