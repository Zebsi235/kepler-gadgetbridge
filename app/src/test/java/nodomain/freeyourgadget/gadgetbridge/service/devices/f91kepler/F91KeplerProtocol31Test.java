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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;

/**
 * Firmware 3.1 wire contract: UiOptions (F2F3) and the Record channel (F2F4).
 *
 * The byte vectors are the ones the HIL rig puts on the wire in its fw-31
 * suites, which pass on a 3.1.0 watch -- so a phone/watch mismatch shows up here
 * as two independent encoders disagreeing, before anyone has to debug it on a
 * wrist.
 */
public class F91KeplerProtocol31Test {

    private static byte[] b(final int... v) {
        final byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    // --- UiOptions ------------------------------------------------------------

    @Test
    public void uiOptions_isUint16LittleEndian() {
        assertArrayEquals(b(0x0F, 0x00), F91KeplerProtocol.uiOptions(0x0F));
        assertArrayEquals(b(0x00, 0x00), F91KeplerProtocol.uiOptions(0));
    }

    @Test
    public void uiOptionBits_mapEachSwitch() {
        assertEquals(0, F91KeplerProtocol.uiOptionBits(false, "en", Locale.ENGLISH, false, false));
        assertEquals(F91KeplerConstants.UIOPT_WEEKDAY,
                F91KeplerProtocol.uiOptionBits(true, "en", Locale.GERMAN, false, false));
        assertEquals(F91KeplerConstants.UIOPT_QUIET_TEXT | F91KeplerConstants.UIOPT_HOURLY_CHIME,
                F91KeplerProtocol.uiOptionBits(false, "en", Locale.ENGLISH, true, true));
        // only the four bits the watch knows, never anything it would refuse
        assertEquals(0x0F, F91KeplerProtocol.uiOptionBits(true, "de", Locale.ENGLISH, true, true));
    }

    @Test
    public void uiOptionBits_autoLanguageFollowsThePhone() {
        assertEquals(F91KeplerConstants.UIOPT_WEEKDAY | F91KeplerConstants.UIOPT_WEEKDAY_DE,
                F91KeplerProtocol.uiOptionBits(true, "auto", Locale.GERMANY, false, false));
        assertEquals(F91KeplerConstants.UIOPT_WEEKDAY,
                F91KeplerProtocol.uiOptionBits(true, "auto", Locale.US, false, false));
        assertEquals(F91KeplerConstants.UIOPT_WEEKDAY,          // explicit English wins
                F91KeplerProtocol.uiOptionBits(true, "en", Locale.GERMANY, false, false));
    }

    // --- Record framing -------------------------------------------------------

    @Test
    public void record_isTypeIndexPayload() {
        assertArrayEquals(b(0x01, 0x02, 1, 7, 30, 0x1F),
                F91KeplerProtocol.record(F91KeplerConstants.REC_ALARM, 2, b(1, 7, 30, 0x1F)));
        assertArrayEquals(b(0x04, 0x00),
                F91KeplerProtocol.record(F91KeplerConstants.REC_FORECAST, 0, new byte[0]));
    }

    // --- Alarm slots ----------------------------------------------------------

    @Test
    public void alarmSlot_usesGadgetbridgesRepetitionMaskAsIs() {
        // GB: ALARM_MON = 1 .. ALARM_SUN = 64 == firmware Monday = bit 0.
        assertEquals(1, Alarm.ALARM_MON);
        assertEquals(64, Alarm.ALARM_SUN);
        final int weekdays = Alarm.ALARM_MON | Alarm.ALARM_TUE | Alarm.ALARM_WED
                | Alarm.ALARM_THU | Alarm.ALARM_FRI;
        assertArrayEquals(b(1, 6, 45, 0x1F), F91KeplerProtocol.alarmSlot(true, 6, 45, weekdays));
        assertArrayEquals(b(1, 23, 59, 0x7F),
                F91KeplerProtocol.alarmSlot(true, 23, 59, Alarm.ALARM_DAILY));
    }

    @Test
    public void alarmSlot_onceIsTheFirmwaresOneShot() {
        assertArrayEquals(b(1, 7, 0, 0), F91KeplerProtocol.alarmSlot(true, 7, 0, Alarm.ALARM_ONCE));
    }

    @Test
    public void alarmSlot_outOfRangeIsSentDisabledNotRefused() {
        assertArrayEquals(b(0, 0, 0, 0), F91KeplerProtocol.alarmSlot(true, 24, 0, 0));
        assertArrayEquals(b(0, 0, 0, 0x01), F91KeplerProtocol.alarmSlot(true, 7, 60, 1));
        assertArrayEquals(b(0, 8, 15, 0x7F), F91KeplerProtocol.alarmSlot(false, 8, 15, 0xFF));
    }

    // --- Counters -------------------------------------------------------------

    @Test
    public void counterName_keepsTheWatchsCount() {
        assertArrayEquals(b(0xFF, 0xFF, 'C', 'O', 'F', 'F', 'E', 'E'),
                F91KeplerProtocol.counterName("COFFEE"));
    }

    @Test
    public void counterName_emptyRemovesTheCounter() {
        assertArrayEquals(b(0xFF, 0xFF), F91KeplerProtocol.counterName(""));
        assertArrayEquals(b(0xFF, 0xFF), F91KeplerProtocol.counterName(null));
        assertArrayEquals(b(0xFF, 0xFF), F91KeplerProtocol.counterName("   "));
    }

    @Test
    public void sanitizeCounterName_onlyWhatTheWatchAccepts() {
        assertEquals("Kaffee", F91KeplerProtocol.sanitizeCounterName("  Kaffee ☕ "));
        assertEquals("Muesli", F91KeplerProtocol.sanitizeCounterName("Müsli"));
        assertEquals("Strasse", F91KeplerProtocol.sanitizeCounterName("Straße"));
        assertEquals("Cafe", F91KeplerProtocol.sanitizeCounterName("Café"));
        assertEquals("ABCDEFGHIJ", F91KeplerProtocol.sanitizeCounterName("ABCDEFGHIJKLMN"));
        assertEquals("AB", F91KeplerProtocol.sanitizeCounterName("A\tB\u0007"));
        for (final char c : F91KeplerProtocol.sanitizeCounterName("Ünïcødé ✓ 漢字").toCharArray()) {
            assertTrue(c >= 0x20 && c <= 0x7E);
        }
    }

    // --- Forecast ---------------------------------------------------------------

    @Test
    public void forecastDay_isConditionHighLowClamped() {
        assertArrayEquals(b(3, 21, 12), F91KeplerProtocol.forecastDay(3, 21, 12));
        assertArrayEquals(b(0, -5, -12), F91KeplerProtocol.forecastDay(0, -5, -12));
        assertArrayEquals(b(7, 127, -128), F91KeplerProtocol.forecastDay(42, 300, -300));
    }

    @Test
    public void tempInUnit_convertsKelvin() {
        assertEquals(20, F91KeplerProtocol.tempInUnit(293.15, false));
        assertEquals(68, F91KeplerProtocol.tempInUnit(293.15, true));
        assertEquals(-10, F91KeplerProtocol.tempInUnit(263.15, false));
    }

    // --- Sunrise / sunset --------------------------------------------------------

    @Test
    public void sunTimes_areLittleEndianMinutes() {
        // 06:12 = 372 = 0x0174, 20:45 = 1245 = 0x04DD
        assertArrayEquals(b(0x74, 0x01, 0xDD, 0x04), F91KeplerProtocol.sunTimes(372, 1245));
        assertArrayEquals(b(0xFF, 0xFF, 0xFF, 0xFF),
                F91KeplerProtocol.sunTimes(F91KeplerConstants.SUN_NONE, F91KeplerConstants.SUN_NONE));
    }

    @Test
    public void sunMinute_isLocalTimeOnToday() {
        final TimeZone berlin = TimeZone.getTimeZone("Europe/Berlin");
        final Calendar today = Calendar.getInstance(berlin);
        today.clear();
        today.set(2026, Calendar.JULY, 1, 12, 0);
        final Calendar rise = (Calendar) today.clone();
        rise.set(Calendar.HOUR_OF_DAY, 5);
        rise.set(Calendar.MINUTE, 3);
        assertEquals(5 * 60 + 3, F91KeplerProtocol.sunMinute(rise.getTimeInMillis() / 1000L, today, berlin));
    }

    @Test
    public void sunMinute_followsSummerTimeOnTheSwitchDay() {
        // 2026-03-29, Europe/Berlin goes CET -> CEST at 02:00. Sunrise 06:59 CEST
        // is 04:59 UTC; the watch wants the local 06:59.
        final TimeZone berlin = TimeZone.getTimeZone("Europe/Berlin");
        final Calendar today = Calendar.getInstance(berlin);
        today.clear();
        today.set(2026, Calendar.MARCH, 29, 12, 0);
        final Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(2026, Calendar.MARCH, 29, 4, 59);
        assertEquals(6 * 60 + 59, F91KeplerProtocol.sunMinute(utc.getTimeInMillis() / 1000L, today, berlin));
    }

    @Test
    public void sunMinute_absentOrNotTodayIsNone() {
        final TimeZone tz = TimeZone.getTimeZone("Europe/Oslo");
        final Calendar today = Calendar.getInstance(tz);
        today.clear();
        today.set(2026, Calendar.JUNE, 21, 12, 0);
        assertEquals(F91KeplerConstants.SUN_NONE, F91KeplerProtocol.sunMinute(0, today, tz));
        final Calendar later = (Calendar) today.clone();
        later.add(Calendar.DAY_OF_YEAR, 40);           // polar day: next set weeks away
        assertEquals(F91KeplerConstants.SUN_NONE,
                F91KeplerProtocol.sunMinute(later.getTimeInMillis() / 1000L, today, tz));
    }

}
