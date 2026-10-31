package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** {@link F91KeplerProtocol#nextAlarm}: the one alarm time firmware before 3.1 can hold. */
public class F91KeplerLegacyAlarmTest {
    private static final TimeZone VIENNA = TimeZone.getTimeZone("Europe/Vienna");
    private static final int ONCE = 0, MON = 1, WED = 4, DAILY = 0x7F;

    private static Calendar at(final int month, final int day, final int hour, final int minute, final int second) {
        final Calendar c = Calendar.getInstance(VIENNA);
        c.clear();
        c.set(2026, month, day, hour, minute, second);
        return c;
    }

    private static void assertRing(final Calendar expected, final Calendar actual) {
        assertEquals(expected.getTimeInMillis(), actual.getTimeInMillis());
    }

    // Wednesday 28 Oct 2026, 20:00:30
    private static final Calendar NOW = at(Calendar.OCTOBER, 28, 20, 0, 30);

    @Test
    public void aDailyAlarmSetInTheEveningRingsTomorrowMorning() {
        assertRing(at(Calendar.OCTOBER, 29, 7, 0, 0), F91KeplerProtocol.nextAlarm(NOW, 7, 0, DAILY));
    }

    @Test
    public void aOneShotRingsAtItsNextTime() {
        assertRing(at(Calendar.OCTOBER, 28, 21, 15, 0), F91KeplerProtocol.nextAlarm(NOW, 21, 15, ONCE));
        assertRing(at(Calendar.OCTOBER, 29, 19, 0, 0), F91KeplerProtocol.nextAlarm(NOW, 19, 0, ONCE));
    }

    @Test
    public void repeatDaysAreKept() {
        assertRing(at(Calendar.NOVEMBER, 2, 7, 0, 0), F91KeplerProtocol.nextAlarm(NOW, 7, 0, MON));
        assertRing(at(Calendar.OCTOBER, 28, 20, 1, 0), F91KeplerProtocol.nextAlarm(NOW, 20, 1, WED));
    }

    @Test
    public void theCurrentMinuteCountsAsPast() {
        assertRing(at(Calendar.NOVEMBER, 4, 20, 0, 0), F91KeplerProtocol.nextAlarm(NOW, 20, 0, WED));
        assertRing(at(Calendar.OCTOBER, 29, 20, 0, 0), F91KeplerProtocol.nextAlarm(NOW, 20, 0, ONCE));
    }

    @Test
    public void staysOnTheWallClockAcrossTheDaylightSavingEnd() {
        // Summer time ends on Sunday 25 Oct 2026 at 03:00
        final Calendar next = F91KeplerProtocol.nextAlarm(at(Calendar.OCTOBER, 24, 12, 0, 0), 7, 0, DAILY);
        assertRing(at(Calendar.OCTOBER, 25, 7, 0, 0), next);
        assertEquals(7, next.get(Calendar.HOUR_OF_DAY));
    }
}
