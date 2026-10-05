package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Watch modes list (F91KeplerModes): the stored value, the migration from
 * the old per-mode position dropdowns, and the ModeOrder bytes each firmware
 * gets. The firmware side of the contract: screen ids 1..13 (FW91
 * f91_screen.h), Main = 0 always first, at most 9 optional screens before
 * 3.1.3 and all 13 from it (FW91 #261), no 3.1 id ever to a pre-3.1 watch.
 */
public class F91KeplerModesTest {

    private static byte[] b(final int... v) {
        final byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    private static List<String> l(final String... v) {
        return Arrays.asList(v);
    }

    // --- the value ---------------------------------------------------------------

    @Test
    public void valuesMapToTheFirmwareScreenIds() {
        assertEquals(1, F91KeplerModes.idOf("notif"));
        assertEquals(9, F91KeplerModes.idOf("image"));
        assertEquals(10, F91KeplerModes.idOf("weather"));
        assertEquals(13, F91KeplerModes.idOf("counter2"));
        assertEquals(-1, F91KeplerModes.idOf("main"));
        assertEquals(F91KeplerConstants.MODE_IMAGE, F91KeplerModes.idOf("image"));
        assertEquals(F91KeplerConstants.MODE_COUNTER2, F91KeplerModes.idOf("counter2"));
    }

    @Test
    public void parseDropsUnknownAndDuplicateValuesAndKeepsOrder() {
        assertEquals(l("timer", "notif", "weather"),
                F91KeplerModes.parse("timer, notif,bogus,timer,weather"));
        assertEquals(l(), F91KeplerModes.parse(""));
        assertEquals(l(), F91KeplerModes.parse(null));
        assertEquals("timer,notif", F91KeplerModes.join(l("timer", "notif")));
    }

    @Test
    public void defaultIsTheNine30ModesInIdOrder() {
        assertEquals(l("notif", "timer", "music", "stopwatch", "info", "flashlight",
                "findphone", "ble", "image"), F91KeplerModes.defaultOrder());
    }

    // --- the bytes ---------------------------------------------------------------

    @Test
    public void defaultOrderIsTheCanonicalFullCycle() {
        assertArrayEquals(b(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
                F91KeplerModes.wireOrder(F91KeplerModes.defaultOrder(), false, 9));
    }

    @Test
    public void nothingTickedIsMainOnly() {
        assertArrayEquals(b(0), F91KeplerModes.wireOrder(l(), true, 13));
    }

    @Test
    public void listOrderIsCycleOrder() {
        assertArrayEquals(b(0, 9, 1, 6),
                F91KeplerModes.wireOrder(l("image", "notif", "flashlight"), false, 9));
    }

    @Test
    public void a30WatchNeverGetsA31Screen() {
        assertArrayEquals(b(0, 1, 2),
                F91KeplerModes.wireOrder(l("notif", "weather", "timer", "counter0"), false, 9));
        assertEquals(0, F91KeplerModes.droppedCount(l("notif", "weather"), false, 9));
    }

    @Test
    public void a31WatchGetsItsScreensWhereverTheyAreInTheList() {
        assertArrayEquals(b(0, 10, 1, 11),
                F91KeplerModes.wireOrder(l("weather", "notif", "counter0"), true, 13));
    }

    @Test
    public void before313TheCycleIsCappedAtNineAndTheRestIsReported() {
        final List<String> all = Arrays.asList(F91KeplerModes.VALUES);
        final byte[] out = F91KeplerModes.wireOrder(all, true, 9);
        assertEquals(10, out.length);
        assertArrayEquals(b(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), out);
        assertEquals(4, F91KeplerModes.droppedCount(all, true, 9));
    }

    @Test
    public void from313AllThirteenFit() {
        final List<String> all = Arrays.asList(F91KeplerModes.VALUES);
        final byte[] out = F91KeplerModes.wireOrder(all, true, 13);
        assertEquals(14, out.length);
        assertEquals(0, F91KeplerModes.droppedCount(all, true, 13));
    }

    @Test
    public void theCapFollowsTheFirmwareVersion() {
        assertEquals(9, F91KeplerModes.maxOptional("3.0.3"));
        assertEquals(9, F91KeplerModes.maxOptional("3.1.2-bl"));
        assertEquals(13, F91KeplerModes.maxOptional("3.1.3-bl"));
        assertEquals(13, F91KeplerModes.maxOptional("3.1.4"));
        assertEquals(13, F91KeplerModes.maxOptional("3.2.0"));
        assertEquals(9, F91KeplerModes.maxOptional(null));      // unknown: the safe side
    }

    // --- migration from the old dropdowns ------------------------------------------

    @Test
    public void legacyPositionsBecomeTheList() {
        final FakePrefs p = new FakePrefs();
        p.edit().putString(F91KeplerConstants.PREF_MODE_POS_IMAGE, "1")
                .putString(F91KeplerConstants.PREF_MODE_POS_NOTIF, "2")
                .putString(F91KeplerConstants.PREF_MODE_POS_TIMER, "0")      // off
                .putString(F91KeplerConstants.PREF_MODE_POS_WEATHER, "3")
                .apply();
        F91KeplerModes.migrate(p);
        // untouched legacy keys keep their old defaults: music 3, stopwatch 4 ...
        assertEquals(l("image", "notif", "music", "stopwatch", "info", "flashlight",
                        "findphone", "ble"),
                F91KeplerModes.parse(p.getString(F91KeplerModes.PREF_MODES, null)));
        // the 3.1 list includes the forecast at its position (3, tie: music has id 3,
        // weather id 10 -> music first)
        assertEquals(l("image", "notif", "music", "weather", "stopwatch", "info",
                        "flashlight", "findphone", "ble"),
                F91KeplerModes.parse(p.getString(F91KeplerModes.PREF_MODES_31, null)));
    }

    @Test
    public void tiesBreakByScreenId() {
        final FakePrefs p = new FakePrefs();
        for (final String k : F91KeplerModes.LEGACY_KEYS) {
            p.edit().putString(k, "0").apply();
        }
        p.edit().putString(F91KeplerConstants.PREF_MODE_POS_MUSIC, "1")
                .putString(F91KeplerConstants.PREF_MODE_POS_TIMER, "1").apply();
        assertEquals(l("timer", "music"), F91KeplerModes.fromLegacyPositions(p, 13));
    }

    @Test
    public void aFreshInstallStartsFromTheDefault() {
        final FakePrefs p = new FakePrefs();
        F91KeplerModes.migrate(p);
        assertEquals(F91KeplerModes.defaultOrder(),
                F91KeplerModes.parse(p.getString(F91KeplerModes.PREF_MODES, null)));
        assertEquals(F91KeplerModes.defaultOrder(),
                F91KeplerModes.parse(p.getString(F91KeplerModes.PREF_MODES_31, null)));
    }

    @Test
    public void migrationNeverOverwritesAnExistingList() {
        final FakePrefs p = new FakePrefs();
        p.edit().putString(F91KeplerModes.PREF_MODES, "ble,notif")
                .putString(F91KeplerModes.PREF_MODES_31, "weather")
                .putString(F91KeplerConstants.PREF_MODE_POS_IMAGE, "1").apply();
        F91KeplerModes.migrate(p);
        F91KeplerModes.migrate(p);
        assertEquals("ble,notif", p.getString(F91KeplerModes.PREF_MODES, null));
        assertEquals("weather", p.getString(F91KeplerModes.PREF_MODES_31, null));
    }

    @Test
    public void anUpgradedWatchStartsItsThirteenListFromItsNineList() {
        final FakePrefs p = new FakePrefs();
        p.edit().putString(F91KeplerModes.PREF_MODES, "music,timer").apply();
        F91KeplerModes.migrate(p);
        assertEquals("music,timer", p.getString(F91KeplerModes.PREF_MODES_31, null));
    }

    @Test
    public void eachFirmwareReadsItsOwnList() {
        final FakePrefs p = new FakePrefs();
        p.edit().putString(F91KeplerModes.PREF_MODES, "music")
                .putString(F91KeplerModes.PREF_MODES_31, "weather,music").apply();
        assertEquals(l("music"), F91KeplerModes.order(p, false));
        assertEquals(l("weather", "music"), F91KeplerModes.order(p, true));
    }

    @Test
    public void legacyKeysAndValuesStayInStep() {
        assertEquals(F91KeplerModes.VALUES.length, F91KeplerModes.LEGACY_KEYS.length);
        assertEquals(13, F91KeplerModes.VALUES.length);
        assertFalse(Arrays.asList(F91KeplerModes.VALUES).contains("main"));
        assertTrue(F91KeplerModes.BASE_COUNT == 9);
    }

    // --- a minimal in-memory SharedPreferences -----------------------------------------

    static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();

        @Override public Map<String, ?> getAll() { return m; }
        @Override public String getString(final String k, final String d) {
            return m.containsKey(k) ? (String) m.get(k) : d;
        }
        @Override public Set<String> getStringSet(final String k, final Set<String> d) { return d; }
        @Override public int getInt(final String k, final int d) { return d; }
        @Override public long getLong(final String k, final long d) { return d; }
        @Override public float getFloat(final String k, final float d) { return d; }
        @Override public boolean getBoolean(final String k, final boolean d) { return d; }
        @Override public boolean contains(final String k) { return m.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener l) { }

        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new HashMap<>();
                @Override public Editor putString(final String k, final String v) { pending.put(k, v); return this; }
                @Override public Editor putStringSet(final String k, final Set<String> v) { return this; }
                @Override public Editor putInt(final String k, final int v) { return this; }
                @Override public Editor putLong(final String k, final long v) { return this; }
                @Override public Editor putFloat(final String k, final float v) { return this; }
                @Override public Editor putBoolean(final String k, final boolean v) { return this; }
                @Override public Editor remove(final String k) { pending.put(k, null); return this; }
                @Override public Editor clear() { m.clear(); return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    for (final Map.Entry<String, Object> e : pending.entrySet()) {
                        if (e.getValue() == null) { m.remove(e.getKey()); } else { m.put(e.getKey(), e.getValue()); }
                    }
                }
            };
        }
    }
}
