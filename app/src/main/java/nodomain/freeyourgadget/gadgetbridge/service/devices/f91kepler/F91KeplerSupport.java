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

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.SharedPreferences;
import android.text.format.DateFormat;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;

/**
 * Talks the F91 Kepler's native GATT services (see {@link F91KeplerConstants}).
 * Maps Gadgetbridge's callbacks onto the watch's capabilities:
 * <ul>
 *   <li>{@link #onSetTime()} → Time + TimeZone characteristics</li>
 *   <li>{@link #onNotification} / {@link #onDeleteNotification} → bar bitmask
 *       (+ optional sender popup)</li>
 *   <li>{@link #onSetCallState} → incoming-call popup</li>
 *   <li>battery → standard Battery Service (read + notify)</li>
 *   <li>{@link #onFindDevice} → flash the display, {@link #onReset} → reboot</li>
 *   <li>{@link #onSendConfiguration} → 12/24h time mode + DST flag</li>
 * </ul>
 */
public class F91KeplerSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerSupport.class);

    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private final GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
    private final BatteryInfoProfile<F91KeplerSupport> batteryInfoProfile;
    private final DeviceInfoProfile<F91KeplerSupport> deviceInfoProfile;
    private final F91KeplerNotificationTracker notificationTracker = new F91KeplerNotificationTracker();

    public F91KeplerSupport() {
        super(LOG);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_NOTIFICATION);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_CLOCK);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_DEVICE_CONTROL);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_WEATHER);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_MUSIC);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_UI_CONFIG);
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);
        addSupportedService(GattService.UUID_SERVICE_DEVICE_INFORMATION);

        final IntentListener batteryListener = intent -> {
            if (BatteryInfoProfile.ACTION_BATTERY_INFO.equals(intent.getAction())) {
                handleBatteryInfo(intent.getParcelableExtra(BatteryInfoProfile.EXTRA_BATTERY_INFO));
            }
        };
        batteryInfoProfile = new BatteryInfoProfile<>(this);
        batteryInfoProfile.addListener(batteryListener);
        addSupportedProfile(batteryInfoProfile);

        final IntentListener deviceInfoListener = intent -> {
            if (DeviceInfoProfile.ACTION_DEVICE_INFO.equals(intent.getAction())) {
                handleDeviceInfo(intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO));
            }
        };
        deviceInfoProfile = new DeviceInfoProfile<>(this);
        deviceInfoProfile.addListener(deviceInfoListener);
        addSupportedProfile(deviceInfoProfile);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    protected TransactionBuilder initializeDevice(final TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        if (GBApplication.getPrefs().syncTime()) {
            addSetTime(builder);
        }
        addTimeMode(builder);
        addDst(builder);
        deviceInfoProfile.requestDeviceInfo(builder);
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        batteryInfoProfile.requestBatteryInfo(builder);
        batteryInfoProfile.enableNotify(builder, true);
        // Subscribe to the Music Control PlaybackCmd notify (P6): the watch is a
        // media remote, so each button press arrives as a notification.
        builder.notify(F91KeplerConstants.UUID_CHAR_MUSIC_CMD, true);
        // Re-push the cached weather on connect: the watch's weather is volatile
        // (RAM only, wiped on reset/reconnect), and GB otherwise only sends on a
        // weather refresh -- so without this a reconnect leaves the slot empty
        // until the next refresh. No-op if GB has no weather yet.
        addWeather(builder);
        return builder;
    }

    /**
     * The watch's Music screen pushes a playback command over the Music Control
     * notify (D2F1). Translate it into a media-control event for the active media
     * player. Other notifications fall through to the base handler.
     */
    @Override
    public boolean onCharacteristicChanged(final BluetoothGatt gatt,
                                           final BluetoothGattCharacteristic characteristic,
                                           final byte[] value) {
        if (F91KeplerConstants.UUID_CHAR_MUSIC_CMD.equals(characteristic.getUuid())
                && value != null && value.length >= 1) {
            final GBDeviceEventMusicControl.Event event = F91KeplerProtocol.musicCommand(value[0]);
            if (event != GBDeviceEventMusicControl.Event.UNKNOWN) {
                handleGBDeviceEvent(new GBDeviceEventMusicControl(event));
                return true;
            }
        }
        return super.onCharacteristicChanged(gatt, characteristic, value);
    }

    private void handleBatteryInfo(final BatteryInfo info) {
        if (info == null) {
            return;
        }
        batteryCmd.level = (short) info.getPercentCharged();
        handleGBDeviceEvent(batteryCmd);
    }

    private void handleDeviceInfo(final DeviceInfo info) {
        if (info == null) {
            return;
        }
        // The firmware DIS exposes the firmware-revision string (0x2A26) only;
        // hardware revision is absent, so leave versionCmd.hwVersion at its default.
        final String fw = info.getFirmwareRevision();
        if (fw != null) {
            versionCmd.fwVersion = fw;
        }
        handleGBDeviceEvent(versionCmd);
    }

    // --- Time ---------------------------------------------------------------

    @Override
    public void onSetTime() {
        final TransactionBuilder builder = createTransactionBuilder("set time");
        addSetTime(builder);
        builder.queue();
    }

    private void addSetTime(final TransactionBuilder builder) {
        final long nowMillis = System.currentTimeMillis();
        // Java's getOffset() returns ms EAST of UTC (incl. DST); firmware stores
        // signed seconds WEST of UTC, so negate. Mirrors the companion app's
        // TimeSyncScheduler — the convention this firmware renders correctly.
        final int secondsWest = -(TimeZone.getDefault().getOffset(nowMillis) / 1000);
        builder.write(F91KeplerConstants.UUID_CHAR_TIME, F91KeplerProtocol.time(nowMillis / 1000L));
        builder.write(F91KeplerConstants.UUID_CHAR_TIMEZONE, F91KeplerProtocol.timezoneWest(secondsWest));
    }

    private void addTimeMode(final TransactionBuilder builder) {
        builder.write(F91KeplerConstants.UUID_CHAR_TIME_MODE, F91KeplerProtocol.timeMode(is24HourMode()));
    }

    private void addDst(final TransactionBuilder builder) {
        final boolean dst = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getBoolean(F91KeplerConstants.PREF_DST, false);
        builder.write(F91KeplerConstants.UUID_CHAR_DST, F91KeplerProtocol.dst(dst));
    }

    private boolean is24HourMode() {
        final String pref = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getString(DeviceSettingsPreferenceConst.PREF_TIMEFORMAT,
                        DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_AUTO);
        if (DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_24H.equals(pref)) {
            return true;
        }
        if (DeviceSettingsPreferenceConst.PREF_TIMEFORMAT_12H.equals(pref)) {
            return false;
        }
        return DateFormat.is24HourFormat(getContext());
    }

    // --- Notifications ------------------------------------------------------

    @Override
    public void onNotification(final NotificationSpec notificationSpec) {
        final F91KeplerNotificationTracker.Category category = categorize(notificationSpec);
        notificationTracker.add(notificationSpec.getId(), category);

        final TransactionBuilder builder = createTransactionBuilder("notification");
        builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_BAR, notificationTracker.bitmask());

        if (isNotificationPopupEnabled()) {
            final String sender = StringUtils.firstNonBlank(
                    notificationSpec.sender, notificationSpec.title, notificationSpec.sourceName);
            if (StringUtils.isNotBlank(sender)) {
                builder.write(F91KeplerConstants.UUID_CHAR_INCOMING_TEXT, F91KeplerProtocol.contactName(sender));
            }
        }
        builder.queue();
    }

    @Override
    public void onDeleteNotification(final int id) {
        notificationTracker.remove(id);
        final TransactionBuilder builder = createTransactionBuilder("delete notification");
        builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_BAR, notificationTracker.bitmask());
        builder.queue();
    }

    private static F91KeplerNotificationTracker.Category categorize(final NotificationSpec spec) {
        final NotificationType type = spec.type;
        if (type == null) {
            return F91KeplerNotificationTracker.Category.TEXT;
        }
        if (type == NotificationType.GENERIC_EMAIL || type.name().contains("MAIL")) {
            return F91KeplerNotificationTracker.Category.EMAIL;
        }
        if (type == NotificationType.GENERIC_PHONE) {
            // Live calls arrive via onSetCallState; a GENERIC_PHONE notification
            // is typically a missed-call / voicemail alert.
            return F91KeplerNotificationTracker.Category.MISSED_CALL;
        }
        return F91KeplerNotificationTracker.Category.TEXT;
    }

    private boolean isNotificationPopupEnabled() {
        return GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getBoolean(F91KeplerConstants.PREF_NOTIFICATION_POPUP, false);
    }

    // --- Calls --------------------------------------------------------------

    @Override
    public void onSetCallState(final CallSpec callSpec) {
        if (callSpec.command != CallSpec.CALL_INCOMING) {
            // The watch auto-clears the popup after ~5s; nothing to do on accept/end.
            return;
        }
        final String name = StringUtils.firstNonBlank(callSpec.name, callSpec.number);
        final TransactionBuilder builder = createTransactionBuilder("incoming call");
        builder.write(F91KeplerConstants.UUID_CHAR_INCOMING_CALL, F91KeplerProtocol.contactName(name));
        builder.queue();
    }

    // --- Weather ------------------------------------------------------------

    @Override
    public void onSendWeather() {
        final TransactionBuilder builder = createTransactionBuilder("send weather");
        addWeather(builder);
        builder.queue();
    }

    /**
     * Append the current cached weather (if any) to a transaction. Shared by
     * {@link #onSendWeather()} and {@link #initializeDevice} so a reconnect
     * restores the watch's volatile weather. No-op when GB has no weather yet.
     */
    private void addWeather(final TransactionBuilder builder) {
        final WeatherSpec weatherSpec = Weather.getWeatherSpec();
        if (weatherSpec == null) {
            return;
        }
        // GB stores temperatures in Kelvin; the watch shows a bare integer in the
        // user's unit (no C/F letter on the face), so convert here per the
        // measurement-system preference.
        final double kelvin = weatherSpec.getCurrentTemp();
        final int celsius = (int) Math.round(kelvin - 273.15);
        final int temp = useFahrenheit() ? (int) Math.round(celsius * 9.0 / 5.0 + 32.0) : celsius;
        final int cond = F91KeplerProtocol.owmToCondition(weatherSpec.getCurrentConditionCode());

        builder.write(F91KeplerConstants.UUID_CHAR_WEATHER_TEMP, F91KeplerProtocol.weatherTemperature(temp));
        builder.write(F91KeplerConstants.UUID_CHAR_WEATHER_CONDITION, F91KeplerProtocol.weatherCondition(cond));
    }

    private boolean useFahrenheit() {
        return "imperial".equals(GBApplication.getPrefs().getString("measurement_system", "metric"));
    }

    // --- Alarms -------------------------------------------------------------

    @Override
    public void onSetAlarms(final ArrayList<? extends Alarm> alarms) {
        // The firmware Alarm Service holds a single one-shot alarm: CHAR5
        // AlarmTime (absolute UTC epoch, 0 = disabled) + CHAR6 AlarmEnabled.
        // Pick the soonest enabled, in-use alarm's next occurrence; if none,
        // disable. (The watch auto-disables after firing, so a repeating alarm
        // effectively fires once until Gadgetbridge re-syncs — recurring is a
        // firmware follow-up, see FW91 #73.)
        Calendar soonest = null;
        for (final Alarm alarm : alarms) {
            if (alarm == null || alarm.getUnused() || !alarm.getEnabled()) {
                continue;
            }
            final Calendar next = AlarmUtils.toCalendar(alarm); // next h:m, rolls to tomorrow if past
            if (soonest == null || next.before(soonest)) {
                soonest = next;
            }
        }

        final TransactionBuilder builder = createTransactionBuilder("set alarms");
        if (soonest == null) {
            builder.write(F91KeplerConstants.UUID_CHAR_ALARM_ENABLED, F91KeplerProtocol.alarmEnabled(false));
            builder.write(F91KeplerConstants.UUID_CHAR_ALARM_TIME, F91KeplerProtocol.alarmTime(0L));
        } else {
            // Write the time first, then enable: the firmware only arms when
            // AlarmEnabled && AlarmTime != 0. getTimeInMillis() is UTC, matching
            // the watch's UTC clock.
            final long epochSeconds = soonest.getTimeInMillis() / 1000L;
            builder.write(F91KeplerConstants.UUID_CHAR_ALARM_TIME, F91KeplerProtocol.alarmTime(epochSeconds));
            builder.write(F91KeplerConstants.UUID_CHAR_ALARM_ENABLED, F91KeplerProtocol.alarmEnabled(true));
            LOG.debug("F91 alarm armed for {} (epoch {})", soonest.getTime(), epochSeconds);
        }
        builder.queue();
    }

    // --- Device control -----------------------------------------------------

    @Override
    public void onFindDevice(final boolean start) {
        final TransactionBuilder builder = createTransactionBuilder("find device");
        if (start) {
            // rev-A has no buzzer; the closest "find" signal is lighting the OLED.
            builder.write(F91KeplerConstants.UUID_CHAR_DEVICE_COMMAND, F91KeplerConstants.CMD_DISPLAY_ON);
            builder.write(F91KeplerConstants.UUID_CHAR_DEVICE_COMMAND, F91KeplerConstants.CMD_DISPLAY_TEST_TEXT);
        } else {
            builder.write(F91KeplerConstants.UUID_CHAR_DEVICE_COMMAND, F91KeplerConstants.CMD_DISPLAY_OFF);
        }
        builder.queue();
    }

    @Override
    public void onReset(final int flags) {
        final TransactionBuilder builder = createTransactionBuilder("reset");
        builder.write(F91KeplerConstants.UUID_CHAR_DEVICE_COMMAND, F91KeplerConstants.CMD_RESET);
        builder.queue();
    }

    // --- Settings -----------------------------------------------------------

    @Override
    public void onSendConfiguration(final String config) {
        super.onSendConfiguration(config);
        switch (config) {
            case DeviceSettingsPreferenceConst.PREF_TIMEFORMAT: {
                final TransactionBuilder builder = createTransactionBuilder("set time mode");
                addTimeMode(builder);
                builder.queue();
                break;
            }
            case F91KeplerConstants.PREF_DST: {
                final TransactionBuilder builder = createTransactionBuilder("set dst");
                addDst(builder);
                builder.queue();
                break;
            }
            case F91KeplerConstants.PREF_MODE_TIMER:
            case F91KeplerConstants.PREF_MODE_MUSIC:
            case F91KeplerConstants.PREF_MODE_STOPWATCH:
            case F91KeplerConstants.PREF_MODE_INFO: {
                final TransactionBuilder builder = createTransactionBuilder("set mode order");
                addModeOrder(builder);
                builder.queue();
                break;
            }
            default:
                break;
        }
    }

    /**
     * Build the ModeOrder from the per-mode enable prefs (Main is always present)
     * and write it to the UI Config char. The watch validates, applies, and
     * persists it (P7).
     */
    private void addModeOrder(final TransactionBuilder builder) {
        final SharedPreferences prefs =
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final byte[] order = F91KeplerProtocol.modeOrder(
                prefs.getBoolean(F91KeplerConstants.PREF_MODE_TIMER, true),
                prefs.getBoolean(F91KeplerConstants.PREF_MODE_MUSIC, true),
                prefs.getBoolean(F91KeplerConstants.PREF_MODE_STOPWATCH, true),
                prefs.getBoolean(F91KeplerConstants.PREF_MODE_INFO, true));
        builder.write(F91KeplerConstants.UUID_CHAR_MODE_ORDER, order);
    }
}
