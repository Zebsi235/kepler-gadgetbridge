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

import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;

/**
 * Default alarms for the Kepler watch. The stock default is the same for every
 * slot (off, 06:30, once), so a fresh alarm list showed five identical rows.
 * The watch has five slots of time + weekday mask and starts with all of them
 * off (firmware f91_alarms.h, Alarms_reset); the app's list is what it gets.
 * So the defaults stay off and only differ in time and days, in the same
 * order as the iOS app: an everyday choice is one switch away.
 * Only new rows get them: an alarm already stored is never rewritten, since a
 * stock-looking 06:30 one-shot may be one the user switches on every night.
 */
public final class F91KeplerAlarms {
    private static final int WEEKDAYS = nodomain.freeyourgadget.gadgetbridge.model.Alarm.ALARM_MON
            | nodomain.freeyourgadget.gadgetbridge.model.Alarm.ALARM_TUE
            | nodomain.freeyourgadget.gadgetbridge.model.Alarm.ALARM_WED
            | nodomain.freeyourgadget.gadgetbridge.model.Alarm.ALARM_THU
            | nodomain.freeyourgadget.gadgetbridge.model.Alarm.ALARM_FRI;
    private static final int WEEKEND = nodomain.freeyourgadget.gadgetbridge.model.Alarm.ALARM_SAT
            | nodomain.freeyourgadget.gadgetbridge.model.Alarm.ALARM_SUN;
    private static final int ONCE = nodomain.freeyourgadget.gadgetbridge.model.Alarm.ALARM_ONCE;

    /** Per slot: hour, minute, repetition. */
    private static final int[][] DEFAULTS = {
            {7, 0, WEEKDAYS},
            {8, 0, WEEKEND},
            {6, 30, WEEKDAYS},
            {9, 0, ONCE},
            {12, 0, ONCE},
    };

    private F91KeplerAlarms() {
    }

    /** Hour, minute and repetition of the default alarm in this slot. */
    public static int[] defaultSlot(final int position) {
        return DEFAULTS[Math.floorMod(position, DEFAULTS.length)].clone();
    }

    /** A new, switched-off default alarm for this slot. */
    public static Alarm create(final long deviceId, final long userId, final int position) {
        final int[] d = defaultSlot(position);
        return new Alarm(deviceId, userId, position, false, false, null, false, d[2], d[0], d[1],
                false, null, null, 0, true);
    }
}
