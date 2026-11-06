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

import java.util.HashSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;

/** Kepler default alarms: off, and no two slots alike. */
public class F91KeplerAlarmsTest {
    @Test
    public void fiveDistinctDefaultsAllOff() {
        final Set<String> seen = new HashSet<>();
        for (int pos = 0; pos < F91KeplerConstants.ALARM_SLOTS_31; pos++) {
            final Alarm a = F91KeplerAlarms.create(1, 2, pos);
            assertFalse("slot " + pos + " must start off", a.getEnabled());
            assertEquals(pos, a.getPosition());
            assertTrue("slot " + pos + " repeats another slot",
                    seen.add(a.getHour() + ":" + a.getMinute() + "/" + a.getRepetition()));
            assertFalse(F91KeplerAlarms.isStockDefault(a.getEnabled(), a.getHour(), a.getMinute(),
                    a.getRepetition(), a.getSmartWakeup(), a.getSnooze(), a.getTitle()));
        }
    }

    @Test
    public void firstSlotIsWeekdaysAtSeven() {
        final Alarm a = F91KeplerAlarms.create(1, 2, 0);
        assertEquals(7, a.getHour());
        assertEquals(0, a.getMinute());
        assertEquals(0x1F, a.getRepetition());
    }

    @Test
    public void onlyTheUntouchedStockAlarmIsReplaced() {
        assertTrue(F91KeplerAlarms.isStockDefault(false, 6, 30, 0, false, false, null));
        assertTrue(F91KeplerAlarms.isStockDefault(false, 6, 30, 0, false, false, ""));
        assertFalse(F91KeplerAlarms.isStockDefault(true, 6, 30, 0, false, false, null));
        assertFalse(F91KeplerAlarms.isStockDefault(false, 6, 31, 0, false, false, null));
        assertFalse(F91KeplerAlarms.isStockDefault(false, 6, 30, 0x1F, false, false, null));
        assertFalse(F91KeplerAlarms.isStockDefault(false, 6, 30, 0, false, true, null));
        assertFalse(F91KeplerAlarms.isStockDefault(false, 6, 30, 0, false, false, "Gym"));
    }
}
