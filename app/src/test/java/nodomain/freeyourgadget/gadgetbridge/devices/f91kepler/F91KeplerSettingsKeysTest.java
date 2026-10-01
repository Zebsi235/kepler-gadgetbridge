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

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Every watch-side setting in the F91 Kepler settings XML must be registered
 * for forwarding (F91KeplerSettingsCustomizer.SEND_KEYS), or a change only
 * reaches the watch on the next reconnect -- which is how the mode order,
 * brightness and sleep window behaved before firmware 3.1 support added the
 * registration. And every registered key must still exist, so the list cannot
 * rot silently when a setting is renamed.
 */
public class F91KeplerSettingsKeysTest {

    private static final String[] XMLS = {
            "devicesettings_f91kepler_brightness.xml",
            "devicesettings_f91kepler_sleep.xml",
            "devicesettings_f91kepler_modes.xml",
            "devicesettings_f91kepler_31_face.xml",
            "devicesettings_f91kepler_31_screens.xml",
    };

    /** Read by the support class when used; nothing to send on change. */
    private static final Set<String> PHONE_ONLY = new HashSet<>(Arrays.asList(
            F91KeplerConstants.PREF_ALERT_TIMER, F91KeplerConstants.PREF_ALERT_ALARM,
            F91KeplerConstants.PREF_ALERT_MODE, F91KeplerConstants.PREF_NOTIFICATION_POPUP,
            F91KeplerConstants.PREF_IMAGE_UPLOAD));

    private static File xmlDir() {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParentFile()) {
            for (final String base : new String[]{"src/main/res/xml", "app/src/main/res/xml"}) {
                final File d = new File(dir, base);
                if (new File(d, XMLS[0]).isFile()) {
                    return d;
                }
            }
        }
        throw new AssertionError("could not locate res/xml from " + new File("").getAbsolutePath());
    }

    /** android:key of every non-category preference in the given files. */
    private static Set<String> settingKeys() throws Exception {
        final Set<String> keys = new HashSet<>();
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        for (final String name : XMLS) {
            final NodeList nodes = f.newDocumentBuilder()
                    .parse(new File(xmlDir(), name)).getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                final Element e = (Element) nodes.item(i);
                final String key = e.getAttribute("android:key");
                if (!key.isEmpty() && !e.getTagName().endsWith("PreferenceCategory")) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    @Test
    public void everyWatchSideSettingIsForwarded() throws Exception {
        final Set<String> send = new HashSet<>(Arrays.asList(F91KeplerSettingsCustomizer.SEND_KEYS));
        for (final String key : settingKeys()) {
            if (!PHONE_ONLY.contains(key)) {
                assertTrue(key + " is never forwarded to the watch -- add it to SEND_KEYS",
                        send.contains(key));
            }
        }
    }

    @Test
    public void everyForwardedKeyStillExists() throws Exception {
        final Set<String> keys = settingKeys();
        for (final String key : F91KeplerSettingsCustomizer.SEND_KEYS) {
            assertTrue(key + " is in SEND_KEYS but in no settings XML", keys.contains(key));
        }
    }
}
