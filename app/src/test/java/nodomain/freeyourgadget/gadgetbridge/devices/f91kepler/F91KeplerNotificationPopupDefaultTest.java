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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Pins the full-screen notification popup to ON by default, on BOTH sides.
 *
 * The popup path is the only one that powers the watch's OLED, so with this
 * preference off a text notification writes the status bar and leaves the panel
 * dark — measured on the HIL rig at 3.4 uA (dark) versus 2365 uA (lit), a 700x
 * difference that is simply "the screen came on or it did not". Incoming calls are
 * not gated by the preference, so calls woke the screen while texts did not, and the
 * watch read as broken (issue #2).
 *
 * There are two independent defaults and they must agree: android:defaultValue in
 * the settings XML decides where the switch sits the first time the screen is
 * opened, and the getBoolean() fallback in F91KeplerSupport decides behaviour before
 * it is ever opened. Flipping only one is a silent behaviour change, so this test
 * reads the XML off disk and compares it against the constant the service uses.
 */
public class F91KeplerNotificationPopupDefaultTest {

    private static final String SETTINGS_XML =
            "src/main/res/xml/devicesettings_f91kepler.xml";

    /**
     * Gradle runs unit tests with the module directory (app/) as the working dir, but
     * that is a convention rather than a guarantee — and a test that fails on the
     * working directory instead of on the defaults would be worse than no test. So
     * walk up from wherever the JVM started until the file turns up.
     */
    private static File settingsXml() {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParentFile()) {
            final File direct = new File(dir, SETTINGS_XML);
            if (direct.isFile()) {
                return direct;
            }
            final File viaApp = new File(dir, "app/" + SETTINGS_XML);
            if (viaApp.isFile()) {
                return viaApp;
            }
        }
        throw new AssertionError("could not locate " + SETTINGS_XML + " from "
                + new File("").getAbsolutePath() + " — if the layout moved, fix this "
                + "lookup rather than deleting the test: the two defaults drifting "
                + "apart is the bug it guards");
    }

    @Test
    public void popupIsOnByDefault() {
        assertTrue("the watch must light up for a notification unless the wearer "
                        + "turns it off — see the 3.4 uA vs 2365 uA measurement",
                F91KeplerConstants.PREF_NOTIFICATION_POPUP_DEFAULT);
    }

    @Test
    public void xmlDefaultMatchesTheConstantTheServiceUses() throws Exception {
        final File xml = settingsXml();
        final String defaultValue = attributeOfPreference(
                new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8),
                F91KeplerConstants.PREF_NOTIFICATION_POPUP,
                "android:defaultValue");

        assertEquals("android:defaultValue in " + SETTINGS_XML + " disagrees with "
                        + "F91KeplerConstants.PREF_NOTIFICATION_POPUP_DEFAULT",
                String.valueOf(F91KeplerConstants.PREF_NOTIFICATION_POPUP_DEFAULT),
                defaultValue);
    }

    /**
     * Returns {@code attr} of the preference element whose android:key is {@code key}.
     * Namespace-unaware on purpose: the attribute is read by its literal prefixed
     * name exactly as it appears in the file, so no namespace fixture is needed.
     */
    private static String attributeOfPreference(final String xmlText,
                                                final String key,
                                                final String attr) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        final DocumentBuilder builder = factory.newDocumentBuilder();
        final NodeList nodes = builder
                .parse(new InputSource(new StringReader(xmlText)))
                .getElementsByTagName("*");

        for (int i = 0; i < nodes.getLength(); i++) {
            final Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            final Element element = (Element) node;
            if (key.equals(element.getAttribute("android:key"))) {
                final String value = element.getAttribute(attr);
                assertTrue("preference '" + key + "' has no " + attr
                        + " — an absent default is not the same as false, and this "
                        + "preference must be explicitly ON", !value.isEmpty());
                return value;
            }
        }
        throw new AssertionError("no preference with android:key='" + key + "' in "
                + SETTINGS_XML);
    }
}
