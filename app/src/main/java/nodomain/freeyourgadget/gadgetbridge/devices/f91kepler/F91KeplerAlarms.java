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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;

/**
 * Default alarms for the Kepler watch. The stock default is the same for every
 * slot (off, 06:30, once), so a fresh alarm list showed five identical rows.
 * The watch has five slots of time + weekday mask and starts with all of them
 * off (firmware f91_alarms.h, Alarms_reset); the app's list is what it gets.
 * So the defaults stay off and only differ in time and days, in the same
 * order as the iOS app: an everyday choice is one switch away.
 */
public final class F91KeplerAlarms {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerAlarms.class);

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

    /** Set once the stored stock defaults of a watch were replaced. */
    static final String PREF_DEFAULTS_SEEDED = "f91_alarm_defaults_seeded";

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

    /** True for an alarm still exactly as Gadgetbridge's stock default made it. */
    public static boolean isStockDefault(final boolean enabled, final int hour, final int minute,
                                         final int repetition, final boolean smartWakeup,
                                         final boolean snooze, final String title) {
        return !enabled && hour == 6 && minute == 30 && repetition == ONCE
                && !smartWakeup && !snooze && (title == null || title.isEmpty());
    }

    /**
     * Once per watch with five slots (firmware 3.1+, version already reported):
     * stored alarms still at the stock default get this slot's default time and
     * days. They stay off, and anything the user changed is left alone, so the
     * watch rings exactly as before. Until all five rows are stored it is
     * simply tried again on the next connect.
     */
    public static void reseedStockDefaults(@NonNull final Context context, @NonNull final GBDevice device) {
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        if (prefs.getBoolean(PREF_DEFAULTS_SEEDED, false)) {
            return;
        }
        final List<Alarm> alarms = DBHelper.getAlarms(device);
        if (alarms.size() < F91KeplerConstants.ALARM_SLOTS_31) {
            return;
        }
        int changed = 0;
        for (final Alarm a : alarms) {
            if (isStockDefault(a.getEnabled(), a.getHour(), a.getMinute(), a.getRepetition(),
                    a.getSmartWakeup(), a.getSnooze(), a.getTitle())) {
                final int[] d = defaultSlot(a.getPosition());
                a.setHour(d[0]);
                a.setMinute(d[1]);
                a.setRepetition(d[2]);
                DBHelper.store(a);
                changed++;
            }
        }
        prefs.edit().putBoolean(PREF_DEFAULTS_SEEDED, true).apply();
        if (changed > 0) {
            LOG.info("F91 replaced {} untouched stock alarm(s) with the Kepler defaults", changed);
            // An open alarm list reloads instead of sending its old rows back.
            LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(new Intent(DeviceService.ACTION_SAVE_ALARMS));
        }
    }
}
