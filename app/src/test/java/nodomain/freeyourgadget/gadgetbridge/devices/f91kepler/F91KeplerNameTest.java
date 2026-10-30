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

/** The names the app finds a watch by, and the old default names it renames once. */
public class F91KeplerNameTest {

    private static boolean accepted(final String advertised) {
        return F91KeplerCoordinator.ADVERTISED_NAMES.matcher(advertised).matches();
    }

    @Test
    public void acceptsAllThreeAdvertisedNames() {
        assertTrue(accepted("F91 Kepler"));
        assertTrue(accepted("Kepler"));
        assertTrue(accepted("Kepler Watch"));
    }

    @Test
    public void rejectsOtherNames() {
        assertFalse(accepted("F91 Kepler Watch"));
        assertFalse(accepted("Kepler2"));
        assertFalse(accepted("kepler"));
        assertFalse(accepted(""));
    }

    @Test
    public void newWatchesAreCalledKeplerWatch() {
        assertEquals("Kepler Watch", F91KeplerCoordinator.DISPLAY_NAME);
    }

    @Test
    public void oldDefaultNamesAreRenamed() {
        assertTrue(F91KeplerCoordinator.isOldDefaultName(null));
        assertTrue(F91KeplerCoordinator.isOldDefaultName(""));
        assertTrue(F91KeplerCoordinator.isOldDefaultName("  "));
        assertTrue(F91KeplerCoordinator.isOldDefaultName("F91 Kepler"));
        assertTrue(F91KeplerCoordinator.isOldDefaultName("Kepler"));
    }

    @Test
    public void namesTheUserTypedAreKept() {
        assertFalse(F91KeplerCoordinator.isOldDefaultName("Kepler Watch"));
        assertFalse(F91KeplerCoordinator.isOldDefaultName("My watch"));
        assertFalse(F91KeplerCoordinator.isOldDefaultName("Kepler 2"));
    }
}
