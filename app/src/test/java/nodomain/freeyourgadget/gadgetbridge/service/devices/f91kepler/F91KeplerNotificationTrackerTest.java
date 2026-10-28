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
package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;

/** The bar mark after a reconnect: only what the phone still shows stays lit. */
public class F91KeplerNotificationTrackerTest {
    @Test
    public void retainAllDropsNotificationsGoneFromThePhone() {
        final F91KeplerNotificationTracker t = new F91KeplerNotificationTracker();
        t.add(1, F91KeplerNotificationTracker.Category.TEXT);
        t.add(2, F91KeplerNotificationTracker.Category.EMAIL);
        assertEquals(F91KeplerConstants.BIT_TEXT | F91KeplerConstants.BIT_EMAIL, t.bitmask() & 0xff);

        t.retainAll(Arrays.asList(2, 99));
        assertEquals(F91KeplerConstants.BIT_EMAIL, t.bitmask() & 0xff);

        t.retainAll(Collections.emptyList());
        assertEquals(0, t.bitmask());
    }
}
