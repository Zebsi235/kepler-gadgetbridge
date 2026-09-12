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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The version gate must (1) read the bench image's {@code -bl} suffix without
 * choking, (2) compare numerically so 2.9 &lt; 2.16 &lt; 3.0, and (3) never hide a
 * setting when the version is unknown -- a device that has not connected yet
 * must show everything, not nothing.
 */
public class F91KeplerFirmwareTest {

    @Test
    public void parsesPlainAndSuffixedVersions() {
        assertArrayEquals(new int[]{3, 0, 0}, F91KeplerFirmware.parse("3.0.0"));
        assertArrayEquals(new int[]{3, 0, 0}, F91KeplerFirmware.parse("3.0.0-bl"));
        assertArrayEquals(new int[]{2, 31, 7}, F91KeplerFirmware.parse("v2.31.7-bl"));
        assertArrayEquals(new int[]{2, 16, 12}, F91KeplerFirmware.parse(" 2.16.12 "));
    }

    @Test
    public void rejectsGarbage() {
        assertNull(F91KeplerFirmware.parse(null));
        assertNull(F91KeplerFirmware.parse(""));
        assertNull(F91KeplerFirmware.parse("unknown"));
        assertNull(F91KeplerFirmware.parse("2.16"));
    }

    @Test
    public void comparesNumericallyNotLexically() {
        assertTrue(F91KeplerFirmware.compare(new int[]{2, 9, 0}, new int[]{2, 16, 0}) < 0);
        assertTrue(F91KeplerFirmware.compare(new int[]{2, 16, 0}, new int[]{3, 0, 0}) < 0);
        assertTrue(F91KeplerFirmware.compare(new int[]{3, 0, 0}, new int[]{3, 0, 0}) == 0);
        assertTrue(F91KeplerFirmware.compare(new int[]{10, 0, 0}, new int[]{9, 99, 99}) > 0);
    }

    @Test
    public void gatesOnlyWhenTheVersionIsKnownAndOlder() {
        // 3.0.0 has everything
        assertFalse(F91KeplerFirmware.knownBelow("3.0.0-bl", F91KeplerFirmware.MIN_BRIGHTNESS));
        assertFalse(F91KeplerFirmware.knownBelow("3.0.0-bl", F91KeplerFirmware.MIN_RADIO_SCHEDULE));
        assertTrue(F91KeplerFirmware.atLeast("3.0.0-bl", F91KeplerFirmware.MIN_ALERTS));
        // the version each feature shipped in is itself capable
        assertFalse(F91KeplerFirmware.knownBelow("2.25.0", F91KeplerFirmware.MIN_BRIGHTNESS));
        assertFalse(F91KeplerFirmware.knownBelow("2.16.0", F91KeplerFirmware.MIN_IMAGE));
        // one below is not
        assertTrue(F91KeplerFirmware.knownBelow("2.24.0", F91KeplerFirmware.MIN_BRIGHTNESS));
        assertTrue(F91KeplerFirmware.knownBelow("2.22.4", F91KeplerFirmware.MIN_ALERTS));
        assertTrue(F91KeplerFirmware.knownBelow("2.25.9", F91KeplerFirmware.MIN_RADIO_SCHEDULE));
        assertTrue(F91KeplerFirmware.knownBelow("2.15.2", F91KeplerFirmware.MIN_MODE_ORDER_10));
        // unknown never hides anything
        assertFalse(F91KeplerFirmware.knownBelow(null, F91KeplerFirmware.MIN_BRIGHTNESS));
        assertFalse(F91KeplerFirmware.knownBelow("", F91KeplerFirmware.MIN_BRIGHTNESS));
        assertFalse(F91KeplerFirmware.knownBelow("garbage", F91KeplerFirmware.MIN_BRIGHTNESS));
        assertFalse(F91KeplerFirmware.atLeast(null, F91KeplerFirmware.MIN_BRIGHTNESS));
    }
}
