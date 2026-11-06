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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import nodomain.freeyourgadget.gadgetbridge.R;

/**
 * A setting the watch's firmware cannot act on is not shown at all (no hint, no
 * greyed-out row), and a watch whose version is not known yet gets the 3.0.3
 * baseline only. These tests read every Kepler settings XML, so a setting added
 * later has to be either listed as baseline here or gated in
 * F91KeplerSettingsCustomizer.GATES.
 */
public class F91KeplerFirmwareGateTest {

    /** What every customer watch (3.0.3 or newer) can do. */
    private static final Set<String> BASELINE = new HashSet<>(Arrays.asList(
            F91KeplerConstants.PREF_BRIGHTNESS,
            F91KeplerConstants.PREF_NOTIFICATION_POPUP, F91KeplerConstants.PREF_RECONNECT_ON_BT_ON,
            "f91_pref_header_alerts", F91KeplerConstants.PREF_ALERT_TIMER,
            F91KeplerConstants.PREF_ALERT_ALARM, F91KeplerConstants.PREF_ALERT_MODE,
            "f91_pref_category_sleep", F91KeplerConstants.PREF_SLEEP_ENABLED,
            F91KeplerConstants.PREF_SLEEP_START, F91KeplerConstants.PREF_SLEEP_END,
            "f91_pref_category_modes", F91KeplerModes.PREF_MODES,
            "f91_pref_category_image", F91KeplerConstants.PREF_IMAGE_UPLOAD));

    private static File resDir() {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParentFile()) {
            for (final String base : new String[]{"src/main/res", "app/src/main/res"}) {
                final File d = new File(dir, base);
                if (new File(d, "xml/devicesettings_f91kepler.xml").isFile()) {
                    return d;
                }
            }
        }
        throw new AssertionError("could not locate res from " + new File("").getAbsolutePath());
    }

    /** Every Kepler settings XML, found by name so a new one cannot be left out. */
    private static File[] keplerXmls() {
        final File[] files = new File(resDir(), "xml").listFiles((d, n) ->
                n.startsWith("devicesettings_f91kepler") && n.endsWith(".xml"));
        assertTrue("no Kepler settings XML found", files != null && files.length >= 8);
        Arrays.sort(files);
        return files;
    }

    /**
     * Each keyed element of the Kepler XMLs (categories too) with the keys of the
     * elements around it, innermost first: hiding a category hides its children.
     */
    private static Map<String, String[]> keysWithParents() throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        final Map<String, String[]> out = new TreeMap<>();
        for (final File xml : keplerXmls()) {
            final NodeList nodes = f.newDocumentBuilder().parse(xml).getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                final Element e = (Element) nodes.item(i);
                final String key = e.getAttribute("android:key");
                if (key.isEmpty()) {
                    continue;
                }
                final java.util.List<String> chain = new java.util.ArrayList<>();
                for (Node n = e; n != null && n.getNodeType() == Node.ELEMENT_NODE; n = n.getParentNode()) {
                    final String k = ((Element) n).getAttribute("android:key");
                    if (!k.isEmpty()) {
                        chain.add(k);
                    }
                }
                out.put(key, chain.toArray(new String[0]));
            }
        }
        return out;
    }

    private static boolean gated(final String key) {
        return F91KeplerSettingsCustomizer.GATES.containsKey(key)
                || Arrays.asList(F91KeplerSettingsCustomizer.KEYS_PRE31).contains(key);
    }

    /** The keys the screen shows for a watch on {@code firmware}. */
    private static Set<String> shown(final String firmware) throws Exception {
        final Set<String> out = new TreeSet<>();
        for (final Map.Entry<String, String[]> e : keysWithParents().entrySet()) {
            boolean visible = true;
            for (final String k : e.getValue()) {
                visible &= F91KeplerSettingsCustomizer.isVisible(k, firmware);
            }
            if (visible) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    @Test
    public void everySettingIsBaselineOrGated() throws Exception {
        for (final Map.Entry<String, String[]> e : keysWithParents().entrySet()) {
            boolean anyGate = false;
            for (final String k : e.getValue()) {
                anyGate |= gated(k);
            }
            final boolean baseline = BASELINE.contains(e.getKey());
            assertTrue(e.getKey() + " is neither 3.0.3 baseline nor gated by a firmware version:"
                    + " add it to F91KeplerSettingsCustomizer.GATES (or to BASELINE here if"
                    + " every 3.0.3 watch has it)", baseline || anyGate);
            assertFalse(e.getKey() + " is listed as baseline but hidden by a firmware gate",
                    baseline && anyGate && !Arrays.asList(F91KeplerSettingsCustomizer.KEYS_PRE31)
                            .contains(e.getKey()));
        }
    }

    @Test
    public void everyGateStillExists() throws Exception {
        final Set<String> keys = keysWithParents().keySet();
        for (final String key : F91KeplerSettingsCustomizer.GATES.keySet()) {
            assertTrue(key + " is in GATES but in no settings XML", keys.contains(key));
        }
        for (final String key : F91KeplerSettingsCustomizer.KEYS_PRE31) {
            assertTrue(key + " is in KEYS_PRE31 but in no settings XML", keys.contains(key));
        }
    }

    @Test
    public void unknownFirmwareShowsTheBaselineOnly() throws Exception {
        final Set<String> base = new TreeSet<>(BASELINE);
        assertEquals(base, shown(null));
        assertEquals(base, shown(""));
        assertEquals(base, shown("unknown"));
        assertEquals(base, shown("3.0.3"));
        assertEquals(base, shown("3.0.3-bl"));
    }

    @Test
    public void threeOneAddsTheFaceAndScreensButNoSound() throws Exception {
        final Set<String> s = shown("3.1.24");
        assertTrue(s.contains(F91KeplerConstants.PREF_QUIET_TEXT));
        assertTrue(s.contains(F91KeplerConstants.PREF_WEEKDAY));
        assertTrue(s.contains(F91KeplerConstants.PREF_HOURLY_CHIME));
        assertTrue(s.contains(F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "0"));
        assertTrue(s.contains(F91KeplerModes.PREF_MODES_31));
        assertFalse("the nine-mode list is replaced on 3.1", s.contains(F91KeplerModes.PREF_MODES));
        assertFalse(s.contains(F91KeplerConstants.PREF_SOUND_MUTE));
        assertFalse(s.contains(F91KeplerConstants.PREF_SOUND_SNOOZE));
        assertFalse(s.contains(F91KeplerConstants.PREF_SECOND_ZONE));
        assertFalse(s.contains(F91KeplerConstants.PREF_SECOND_ZONE_ID));
    }

    @Test
    public void soundAndLostAlertFollowTheirVersions() throws Exception {
        final Set<String> s321 = shown("3.2.1");
        assertTrue(s321.contains(F91KeplerConstants.PREF_SOUND_MUTE));
        assertTrue(s321.contains(F91KeplerConstants.PREF_SOUND_SNOOZE));
        assertFalse("lost-phone alert needs 3.2.2", s321.contains(F91KeplerConstants.PREF_SOUND_LOST));
        assertFalse("tone choice needs 3.3.0", s321.contains(F91KeplerConstants.PREF_SOUND_ALARM_TONE));
        assertFalse("tone choice needs 3.3.0", s321.contains(F91KeplerConstants.PREF_SOUND_CHIME_TONE));
        assertFalse(shown("3.2.2").contains(F91KeplerConstants.PREF_SOUND_CHIME_TONE));
        final Set<String> s330 = shown("3.3.0");
        assertTrue(s330.contains(F91KeplerConstants.PREF_SOUND_LOST));
        assertTrue(s330.contains(F91KeplerConstants.PREF_SOUND_ALARM_TONE));
        assertTrue(s330.contains(F91KeplerConstants.PREF_SOUND_CHIME_TONE));
        assertFalse(s330.contains(F91KeplerConstants.PREF_SECOND_ZONE));
    }

    @Test
    public void threeThreeTwoShowsEverything() throws Exception {
        final Set<String> s = shown("3.3.2-bl");
        for (final String key : keysWithParents().keySet()) {
            if (!key.equals(F91KeplerModes.PREF_MODES)) {
                assertTrue(key + " hidden on 3.3.2", s.contains(key));
            }
        }
    }

    @Test
    public void hourlyChimeSummaryMatchesTheWatch() {
        assertEquals(R.string.f91_pref_summary_hourly_chime, F91KeplerSettingsCustomizer.hourlyChimeSummary(null));
        assertEquals(R.string.f91_pref_summary_hourly_chime, F91KeplerSettingsCustomizer.hourlyChimeSummary("3.1.24"));
        assertEquals(R.string.f91_pref_summary_hourly_chime_beep, F91KeplerSettingsCustomizer.hourlyChimeSummary("3.3.0"));
    }

    /** The tone lists offer exactly F91KeplerConstants.TONE_CHOICES, one name per tone. */
    @Test
    public void toneListMatchesTheOfferedTones() throws Exception {
        final NodeList arrays = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File(resDir(), "values/arrays.xml")).getElementsByTagName("string-array");
        int names = -1;
        java.util.List<Integer> values = null;
        for (int i = 0; i < arrays.getLength(); i++) {
            final Element a = (Element) arrays.item(i);
            final NodeList items = a.getElementsByTagName("item");
            if ("f91_sound_tones".equals(a.getAttribute("name"))) {
                names = items.getLength();
            } else if ("f91_sound_tones_values".equals(a.getAttribute("name"))) {
                values = new java.util.ArrayList<>();
                for (int j = 0; j < items.getLength(); j++) {
                    values.add(Integer.parseInt(items.item(j).getTextContent().trim()));
                }
            }
        }
        final java.util.List<Integer> offered = new java.util.ArrayList<>();
        for (final int t : F91KeplerConstants.TONE_CHOICES) {
            offered.add(t);
        }
        assertEquals(offered, values);
        assertEquals(offered.size(), names);
    }

    /** No "needs firmware ..." or "from 3.2" hints in any Kepler text, in any language. */
    @Test
    public void noKeplerTextNamesAFirmwareVersion() throws Exception {
        final Pattern hint = Pattern.compile("(?i)firmware|\\b[23]\\.\\d+(\\.\\d+)?\\b");
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        int checked = 0;
        final File[] dirs = resDir().listFiles((d, n) -> n.startsWith("values"));
        assertTrue(dirs != null);
        for (final File dir : dirs) {
            final File[] files = dir.listFiles((d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
            if (files == null) {
                continue;
            }
            for (final File xml : files) {
                final NodeList strings = f.newDocumentBuilder().parse(xml).getElementsByTagName("string");
                for (int i = 0; i < strings.getLength(); i++) {
                    final Element e = (Element) strings.item(i);
                    if (!e.getAttribute("name").startsWith("f91_")) {
                        continue;
                    }
                    checked++;
                    assertFalse(dir.getName() + "/" + e.getAttribute("name") + " names a firmware version: "
                            + e.getTextContent(), hint.matcher(e.getTextContent()).find());
                }
            }
        }
        assertTrue("no Kepler strings found", checked > 50);
    }
}
