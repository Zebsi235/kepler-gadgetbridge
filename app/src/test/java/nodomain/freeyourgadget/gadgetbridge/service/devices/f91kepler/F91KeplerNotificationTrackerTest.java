package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;

public class F91KeplerNotificationTrackerTest {
    private static final long T0 = 1_800_000_000_000L;
    private static final long DAY = F91KeplerNotificationTracker.UNTRACKED_MAX_AGE_MS;

    @Test
    public void anUntrackedNotificationEndsAfterADay() {
        final F91KeplerNotificationTracker t = new F91KeplerNotificationTracker();
        t.addUntracked(7, F91KeplerNotificationTracker.Category.TEXT, T0);
        assertTrue(t.expire(T0 + DAY - 1).isEmpty());
        assertEquals(F91KeplerConstants.BIT_TEXT, t.bitmask());
        assertEquals(Collections.singletonList(7), t.expire(T0 + DAY));
        assertEquals(0, t.bitmask());
        assertTrue("reported once", t.expire(T0 + 2 * DAY).isEmpty());
    }

    @Test
    public void listenerNotificationsNeverExpire() {
        final F91KeplerNotificationTracker t = new F91KeplerNotificationTracker();
        t.add(1, F91KeplerNotificationTracker.Category.EMAIL);
        t.addUntracked(2, F91KeplerNotificationTracker.Category.EMAIL, T0);
        assertEquals(Collections.singletonList(2), t.expire(T0 + 10 * DAY));
        assertEquals(F91KeplerConstants.BIT_EMAIL, t.bitmask());
    }

    @Test
    public void aDismissedOneIsNotReportedLater() {
        final F91KeplerNotificationTracker t = new F91KeplerNotificationTracker();
        t.addUntracked(3, F91KeplerNotificationTracker.Category.MISSED_CALL, T0);
        t.remove(3);
        assertEquals(0, t.bitmask());
        assertTrue(t.expire(T0 + DAY).isEmpty());
    }

    @Test
    public void aClockSetBackDoesNotKeepTheMarkForEver() {
        final F91KeplerNotificationTracker t = new F91KeplerNotificationTracker();
        t.addUntracked(4, F91KeplerNotificationTracker.Category.TEXT, T0);
        assertEquals(Collections.singletonList(4), t.expire(T0 - 60_000));
    }
}
