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
import android.widget.Toast;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerFirmware;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerImageCodec;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerImageStore;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;
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
 *   <li>{@link #onFindDevice} → flash the "FIND" alert on the watch,
 *       {@link #onReset} → reboot</li>
 *   <li>{@link #onSendConfiguration} → 12/24h time mode, DST flag, mode order,
 *       display brightness, sleep window, image upload</li>
 * </ul>
 */
public class F91KeplerSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerSupport.class);

    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private final GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
    private final BatteryInfoProfile<F91KeplerSupport> batteryInfoProfile;
    private final DeviceInfoProfile<F91KeplerSupport> deviceInfoProfile;
    private final F91KeplerNotificationTracker notificationTracker = new F91KeplerNotificationTracker();

    /** Recent notifications for the watch's Notifications mode, newest first,
     *  capped at the firmware's ring size. GB owns the active set; the watch is
     *  told the current top-N (so dismissals are reflected). */
    private static final int F91_RECENT_MAX = 5;

    /** Upload attempts per reconciliation round: the first try plus one retry. */
    private static final int IMAGE_MAX_ATTEMPTS = 2;
    /** Uploads spent on the current frame; reset once the watch confirms it.
     *  Touched from both the service thread and the GATT callback thread. */
    private volatile int imageAttempts;
    /** Set when a round ran out of attempts, so a watch that keeps refusing the
     *  image is not re-uploaded to (and re-toasted about) on every reconnect.
     *  Cleared when the user sends an image again. */
    private volatile boolean imageGaveUp;

    private final List<RecentNotif> recent = new ArrayList<>();
    private static final class RecentNotif {
        final int id;
        final String app;
        final String sender;
        RecentNotif(final int id, final String app, final String sender) {
            this.id = id; this.app = app; this.sender = sender;
        }
    }

    public F91KeplerSupport() {
        super(LOG);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_NOTIFICATION);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_CLOCK);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_DEVICE_CONTROL);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_WEATHER);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_MUSIC);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_FIND_PHONE);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_ALERT);   // issue #209
        addSupportedService(F91KeplerConstants.UUID_SERVICE_UI_CONFIG);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_IMAGE);
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
        // The sleep window is re-pushed on every connect (issue #213): a
        // reflashed watch or a wiped SNV would otherwise spend the night
        // reachable when it was asked to be quiet, and the user would have to
        // notice and toggle the setting to fix it.
        addRadioSchedule(builder);
        deviceInfoProfile.requestDeviceInfo(builder);
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        batteryInfoProfile.requestBatteryInfo(builder);
        batteryInfoProfile.enableNotify(builder, true);
        // Subscribe to the Music Control PlaybackCmd notify (P6): the watch is a
        // media remote, so each button press arrives as a notification.
        builder.notify(F91KeplerConstants.UUID_CHAR_MUSIC_CMD, true);
        // Subscribe to the Find Phone notify: a button on the watch's Find Phone
        // mode rings/stops this phone.
        builder.notify(F91KeplerConstants.UUID_CHAR_FIND_PHONE_CMD, true);
        // The watch pushes an AlertEvent when its timer expires or its alarm
        // fires, so this phone can ring/vibrate as a backup (issue #209).
        builder.notify(F91KeplerConstants.UUID_CHAR_ALERT_EVENT, true);
        // Re-push the cached weather on connect: the watch's weather is volatile
        // (RAM only, wiped on reset/reconnect), and GB otherwise only sends on a
        // weather refresh -- so without this a reconnect leaves the slot empty
        // until the next refresh. No-op if GB has no weather yet.
        addWeather(builder);
        // Likewise re-push the notification history (also volatile on the watch).
        addRecentNotifications(builder);
        // And the uploaded image -- but that one is 28 writes, so ask the watch
        // what it is holding first instead of re-sending it on every reconnect.
        addImageCheck(builder);
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
        if (F91KeplerConstants.UUID_CHAR_FIND_PHONE_CMD.equals(characteristic.getUuid())
                && value != null && value.length >= 1) {
            final GBDeviceEventFindPhone.Event event = F91KeplerProtocol.findPhoneCommand(value[0]);
            if (event != GBDeviceEventFindPhone.Event.UNKNOWN) {
                final GBDeviceEventFindPhone fp = new GBDeviceEventFindPhone();
                fp.event = event;
                handleGBDeviceEvent(fp);
                return true;
            }
        }
        if (F91KeplerConstants.UUID_CHAR_ALERT_EVENT.equals(characteristic.getUuid())
                && value != null && value.length >= 1) {
            handleAlertEvent(value[0]);
            return true;
        }
        return super.onCharacteristicChanged(gatt, characteristic, value);
    }

    /**
     * Raise a phone-side alert for a watch timer/alarm event (issue #209).
     *
     * <p>Gated per event type, because the two are wanted independently: a timer
     * is usually deliberate and nearby, an alarm often is not. If the relevant
     * switch is off the event is swallowed silently -- the watch has no way to
     * know the phone's preference and should not need one.
     */
    private void handleAlertEvent(final byte ev) {
        final SharedPreferences prefs =
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final boolean enabled;
        if (ev == F91KeplerConstants.ALERT_EVENT_ALARM) {
            enabled = prefs.getBoolean(F91KeplerConstants.PREF_ALERT_ALARM, true);
        } else if (ev == F91KeplerConstants.ALERT_EVENT_TIMER) {
            enabled = prefs.getBoolean(F91KeplerConstants.PREF_ALERT_TIMER, true);
        } else {
            enabled = false;    // unknown event: never guess
        }
        if (!enabled) {
            return;
        }
        final boolean vibrateOnly = "vibrate".equals(
                prefs.getString(F91KeplerConstants.PREF_ALERT_MODE, "ring"));
        final GBDeviceEventFindPhone.Event event =
                F91KeplerProtocol.alertEvent(ev, vibrateOnly);
        if (event != GBDeviceEventFindPhone.Event.UNKNOWN) {
            final GBDeviceEventFindPhone fp = new GBDeviceEventFindPhone();
            fp.event = event;
            handleGBDeviceEvent(fp);
        }
    }

    /**
     * ImageControl (A3F2) reads report {@code [valid][xor8]}, which is the watch's
     * own account of the image it is holding. Every image decision runs through
     * this one answer -- see {@link #reconcileImage}.
     */
    @Override
    public boolean onCharacteristicRead(final BluetoothGatt gatt,
                                        final BluetoothGattCharacteristic characteristic,
                                        final byte[] value, final int status) {
        if (F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL.equals(characteristic.getUuid())) {
            reconcileImage(status == BluetoothGatt.GATT_SUCCESS ? value : null);
            return true;
        }
        return super.onCharacteristicRead(gatt, characteristic, value, status);
    }

    /**
     * A rejected image write is the failure the commit checksum exists to catch,
     * and it also cancels the rest of its transaction -- so the verification read
     * queued behind it never runs. Report it here instead, so the upload still
     * gets its one retry and the user still hears about a persistent failure.
     */
    @Override
    public boolean onCharacteristicWrite(final BluetoothGatt gatt,
                                         final BluetoothGattCharacteristic characteristic,
                                         final int status) {
        if (status != BluetoothGatt.GATT_SUCCESS && characteristic != null
                && (F91KeplerConstants.UUID_CHAR_IMAGE_CHUNK.equals(characteristic.getUuid())
                    || F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL.equals(characteristic.getUuid()))) {
            LOG.warn("F91 image write to {} failed with status {}", characteristic.getUuid(), status);
            reconcileImage(null);
        }
        return super.onCharacteristicWrite(gatt, characteristic, status);
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
        restorePhoneOwnedConfig(fw);
    }

    /**
     * Once the firmware version is known for THIS connection, put back the
     * settings that only the phone owns and that a reflash, SNV wipe or factory
     * reset on the watch would have lost -- the same reasoning as the sleep
     * window in initializeDevice, but gated on the version so an older firmware
     * without the characteristic is never written to (an ATT error in the init
     * transaction would abort it).
     *
     * Mode order: phone-owned, and the firmware skips the SNV write when the
     * order is unchanged, so re-sending it on every connect costs nothing.
     * Brightness: only if it was changed while the watch was away (dirty flag),
     * because SW3 on the watch's Flashlight screen changes it too and a blind
     * re-push would undo that.
     */
    private void restorePhoneOwnedConfig(final String fw) {
        final SharedPreferences prefs =
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final TransactionBuilder builder = createTransactionBuilder("restore phone-owned config");
        boolean any = false;
        if (F91KeplerFirmware.atLeast(fw, F91KeplerFirmware.MIN_MODE_ORDER_10)) {
            addModeOrder(builder);
            any = true;
        }
        if (prefs.getBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, false)
                && F91KeplerFirmware.atLeast(fw, F91KeplerFirmware.MIN_BRIGHTNESS)) {
            addBrightness(builder);
            prefs.edit().putBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, false).apply();
            any = true;
        }
        if (any) {
            builder.queue();
        }
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
        // Always 0. The offset written by addSetTime already includes summer time
        // (TimeZone.getOffset), and the firmware adds one more hour on top when
        // this flag is 1 -- the old user switch therefore put the face an hour
        // ahead. Writing 0 on every connect also clears a stale 1 left by that
        // switch. The iOS app does the same.
        builder.write(F91KeplerConstants.UUID_CHAR_DST, F91KeplerProtocol.dst(false));
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
        addRecent(notificationSpec);

        final TransactionBuilder builder = createTransactionBuilder("notification");
        builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_BAR, notificationTracker.bitmask());

        if (isNotificationPopupEnabled()) {
            final String sender = StringUtils.firstNonBlank(
                    notificationSpec.sender, notificationSpec.title, notificationSpec.sourceName);
            if (StringUtils.isNotBlank(sender)) {
                // Split-tile popup: send the app label (sourceName) + sender so the
                // watch can show "<app> / <sender> / TEXT". The serializer keeps the
                // whole payload within the characteristic's byte budget.
                final String app = StringUtils.firstNonBlank(notificationSpec.sourceName, "");
                builder.write(F91KeplerConstants.UUID_CHAR_INCOMING_TEXT,
                              F91KeplerProtocol.incomingTextPopup(app, sender));
            }
        }
        addRecentNotifications(builder);   // push the updated history list to the watch
        builder.queue();
    }

    @Override
    public void onDeleteNotification(final int id) {
        notificationTracker.remove(id);
        removeRecent(id);
        final TransactionBuilder builder = createTransactionBuilder("delete notification");
        builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_BAR, notificationTracker.bitmask());
        addRecentNotifications(builder);   // reflect the dismissal in the history list
        builder.queue();
    }

    // --- Notification history (Notifications mode) --------------------------

    private void addRecent(final NotificationSpec spec) {
        final String app = StringUtils.firstNonBlank(spec.sourceName, "");
        final String sender = StringUtils.firstNonBlank(spec.sender, spec.title, "notification");
        removeRecent(spec.getId());                    // de-dupe by id
        recent.add(0, new RecentNotif(spec.getId(), app, sender));   // newest first
        while (recent.size() > F91_RECENT_MAX) {
            recent.remove(recent.size() - 1);
        }
    }

    private void removeRecent(final int id) {
        for (final Iterator<RecentNotif> it = recent.iterator(); it.hasNext(); ) {
            if (it.next().id == id) {
                it.remove();
                break;
            }
        }
    }

    /** Push the current top-N recent notifications (or a clear when empty) to the
     *  watch's NotificationEntry char. */
    private void addRecentNotifications(final TransactionBuilder builder) {
        final int total = recent.size();
        if (total == 0) {
            builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_ENTRY,
                          F91KeplerProtocol.notificationEntry(0, 0, "", ""));
            return;
        }
        for (int i = 0; i < total; i++) {
            final RecentNotif e = recent.get(i);
            builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_ENTRY,
                          F91KeplerProtocol.notificationEntry(i, total, e.app, e.sender));
        }
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

    /**
     * Whether a notification should raise the watch's full-screen sender popup.
     *
     * Defaults to TRUE, and the default matters more than it looks: the popup path
     * is the only one that powers the OLED, so with this off a text notification
     * writes the status bar and leaves the panel dark. Measured on the HIL rig with
     * the panel off: a bar write settles at 3.4 uA (dark), a popup write draws
     * 2365 uA (lit) — a 700x difference that is simply "the screen came on or it
     * did not". A watch that never lights up for a message reads as broken, which
     * is exactly how this was reported (kepler-gadgetbridge #2). Incoming calls are
     * deliberately NOT gated by this preference, so calls used to wake the screen
     * while texts did not — the inconsistency that made it look like a defect.
     *
     * The cost is affordable: one popup is 2.63 uAh, and the realistic-wear budget
     * in FW91 docs/POWER_PLAN.md 9.4 already counts 50 notifications a day as
     * lighting the panel.
     *
     * The default itself lives in {@link F91KeplerConstants#PREF_NOTIFICATION_POPUP_DEFAULT}
     * so that it can be pinned against android:defaultValue in
     * res/xml/devicesettings_f91kepler.xml by a unit test: the XML governs the
     * switch's initial position, this fallback governs behaviour before the settings
     * screen has ever been opened, and the two disagreeing is a silent behaviour
     * change of exactly the kind this issue was.
     */
    private boolean isNotificationPopupEnabled() {
        return GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getBoolean(F91KeplerConstants.PREF_NOTIFICATION_POPUP,
                        F91KeplerConstants.PREF_NOTIFICATION_POPUP_DEFAULT);
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
        // rev-A has no buzzer; the loudest "find" signal is a full-screen
        // alert that blinks the panel on/off. The firmware starts it (FIND_ON)
        // / stops it (FIND_OFF); any watch button also dismisses it.
        builder.write(F91KeplerConstants.UUID_CHAR_DEVICE_COMMAND,
                start ? F91KeplerConstants.CMD_FIND_ON : F91KeplerConstants.CMD_FIND_OFF);
        builder.queue();
    }

    @Override
    public void onReset(final int flags) {
        // Debug screen: "Reboot" -> 0x01 deferred reset; "Factory reset" -> 0x16,
        // which erases every bond on the watch and reboots. After 0x16 the phone
        // still holds its side of the bond and must forget the watch in Android's
        // Bluetooth settings before pairing again -- that is why the ordinary UI
        // never offers it.
        final boolean factory = (flags & GBDeviceProtocol.RESET_FLAGS_FACTORY_RESET) != 0;
        final TransactionBuilder builder = createTransactionBuilder(factory ? "factory reset" : "reset");
        builder.write(F91KeplerConstants.UUID_CHAR_DEVICE_COMMAND,
                      factory ? F91KeplerConstants.CMD_CLEAR_BONDS : F91KeplerConstants.CMD_RESET);
        builder.queue();
    }

    // --- Image (Image mode) -------------------------------------------------

    /**
     * Ask the watch what image it holds, but only when we have one to compare
     * against. The answer arrives in {@link #onCharacteristicRead}.
     */
    private void addImageCheck(final TransactionBuilder builder) {
        imageAttempts = 0;
        if (imageGaveUp || F91KeplerImageStore.load(getDevice()) == null) {
            return;
        }
        builder.read(F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL);
    }

    /**
     * Decide what to do about the stored image: a matching checksum means the
     * watch already has it, anything else means upload. Passing {@code null}
     * states "the watch does not have it" without asking.
     *
     * <p>Every image decision funnels through here, and each call spends at most
     * one upload, so the round is bounded to the first try plus one retry before
     * the user is told. Nothing here trusts a local flag: the watch either
     * confirms the checksum or it does not.
     */
    private void reconcileImage(final byte[] control) {
        final byte[] frame = F91KeplerImageStore.load(getDevice());
        if (frame == null) {
            imageAttempts = 0;
            return;
        }
        if (F91KeplerProtocol.imageControlMatches(control, F91KeplerImageCodec.xor8(frame))) {
            LOG.debug("F91 watch already holds the stored image, skipping the upload");
            imageAttempts = 0;
            return;
        }
        if (imageAttempts >= IMAGE_MAX_ATTEMPTS) {
            LOG.warn("F91 image not confirmed after {} upload attempts, giving up", imageAttempts);
            imageAttempts = 0;
            imageGaveUp = true;
            GB.toast(getContext(), getContext().getString(R.string.f91_image_upload_failed),
                     Toast.LENGTH_LONG, GB.ERROR);
            return;
        }
        imageAttempts++;
        LOG.debug("F91 uploading the stored image (attempt {})", imageAttempts);
        sendImage(frame);
    }

    /**
     * Upload one frame: begin, the 26 chunks, then commit with the checksum. The
     * writes are write-with-response and the queue preserves their order, so the
     * firmware sees a complete buffer before it latches.
     *
     * <p>Two things can then happen, and both come back to
     * {@link #reconcileImage}. If every write is accepted, the trailing read
     * reports the checksum the watch actually latched. If a write is rejected --
     * which is exactly what the firmware does to a commit whose checksum does not
     * match its buffer -- the queue abandons the rest of this transaction,
     * including that read, so {@link #onCharacteristicWrite} reports the failure
     * instead.
     */
    private void sendImage(final byte[] frame) {
        final TransactionBuilder builder = createTransactionBuilder("send image");
        builder.write(F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL, F91KeplerProtocol.imageBegin());
        for (final byte[] chunk : F91KeplerProtocol.imageChunks(frame)) {
            builder.write(F91KeplerConstants.UUID_CHAR_IMAGE_CHUNK, chunk);
        }
        builder.write(F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL,
                      F91KeplerProtocol.imageCommit(F91KeplerImageCodec.xor8(frame)));
        builder.read(F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL);
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
            case F91KeplerConstants.PREF_MODE_POS_NOTIF:
            case F91KeplerConstants.PREF_MODE_POS_TIMER:
            case F91KeplerConstants.PREF_MODE_POS_MUSIC:
            case F91KeplerConstants.PREF_MODE_POS_STOPWATCH:
            case F91KeplerConstants.PREF_MODE_POS_INFO:
            case F91KeplerConstants.PREF_MODE_POS_FLASHLIGHT:
            case F91KeplerConstants.PREF_MODE_POS_FINDPHONE:
            case F91KeplerConstants.PREF_MODE_POS_BLE:
            case F91KeplerConstants.PREF_MODE_POS_IMAGE: {
                final TransactionBuilder builder = createTransactionBuilder("set mode order");
                addModeOrder(builder);
                builder.queue();
                break;
            }
            case F91KeplerConstants.PREF_BRIGHTNESS: {
                if (!getDevice().isInitialized()) {
                    // Nothing to write to: remember it and let handleDeviceInfo
                    // push it on the next connect, once the firmware is known to
                    // have F2F2. Without this the change was silently lost.
                    GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                            .edit().putBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, true).apply();
                    break;
                }
                final TransactionBuilder builder = createTransactionBuilder("set brightness");
                addBrightness(builder);
                builder.queue();
                break;
            }
            case F91KeplerConstants.PREF_SLEEP_ENABLED:
            case F91KeplerConstants.PREF_SLEEP_START:
            case F91KeplerConstants.PREF_SLEEP_END: {
                final TransactionBuilder builder = createTransactionBuilder("set sleep window");
                addRadioSchedule(builder);
                builder.queue();
                break;
            }
            case F91KeplerConstants.PREF_IMAGE_UPLOAD: {
                // The activity has already stored the packed frame; this is just
                // "send it now". Counts as the first of the two attempts.
                final byte[] frame = F91KeplerImageStore.load(getDevice());
                if (frame == null) {
                    LOG.warn("F91 image upload requested but no frame is stored");
                    break;
                }
                // An explicit send is also the way out of a given-up round.
                imageGaveUp = false;
                imageAttempts = 1;
                sendImage(frame);
                break;
            }
            default:
                break;
        }
    }

    /**
     * Build the ModeOrder from the per-mode position prefs (Main is always first;
     * each optional mode's position 1..9 sets its slot, "0" = off) and write it
     * to the UI Config char. The watch validates, applies, and persists it.
     * Sent on change and re-sent on every connect (restorePhoneOwnedConfig) so a
     * reflashed or reset watch gets its order back; the firmware ignores an
     * unchanged order without touching flash.
     * Defaults give the canonical order Notifications, Timer, Music, Stopwatch,
     * Info, Flashlight, Find Phone, Bluetooth, Image.
     */
    private void addModeOrder(final TransactionBuilder builder) {
        final SharedPreferences prefs =
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final byte[] order = F91KeplerProtocol.modeOrder(
                modePos(prefs, F91KeplerConstants.PREF_MODE_POS_NOTIF, 1),
                modePos(prefs, F91KeplerConstants.PREF_MODE_POS_TIMER, 2),
                modePos(prefs, F91KeplerConstants.PREF_MODE_POS_MUSIC, 3),
                modePos(prefs, F91KeplerConstants.PREF_MODE_POS_STOPWATCH, 4),
                modePos(prefs, F91KeplerConstants.PREF_MODE_POS_INFO, 5),
                modePos(prefs, F91KeplerConstants.PREF_MODE_POS_FLASHLIGHT, 6),
                modePos(prefs, F91KeplerConstants.PREF_MODE_POS_FINDPHONE, 7),
                modePos(prefs, F91KeplerConstants.PREF_MODE_POS_BLE, 8),
                modePos(prefs, F91KeplerConstants.PREF_MODE_POS_IMAGE, 9));
        builder.write(F91KeplerConstants.UUID_CHAR_MODE_ORDER, order);
    }

    /**
     * Write the display brightness step to the UI Config Brightness char (issue
     * #211). The watch applies it immediately and persists it in SNV. Sent on a
     * preference change while connected; a change made while the watch is away
     * sets PREF_BRIGHTNESS_DIRTY and is pushed by restorePhoneOwnedConfig on the
     * next connect. Deliberately not re-pushed blindly on every connect: SW3 on
     * the watch's Flashlight screen changes brightness too.
     */
    private void addBrightness(final TransactionBuilder builder) {
        final SharedPreferences prefs =
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final int step = modePos(prefs, F91KeplerConstants.PREF_BRIGHTNESS,
                                 F91KeplerConstants.BRIGHTNESS_DEFAULT);
        builder.write(F91KeplerConstants.UUID_CHAR_BRIGHTNESS,
                      F91KeplerProtocol.brightness(step));
    }

    /**
     * Write the scheduled radio-off window to B2F7 (issue #213).
     *
     * Re-pushed on connect as well as on change, which the issue asks for
     * explicitly: a watch that was reflashed or had its SNV wiped gets its sleep
     * window back without the user thinking about it. The write is cheap (5
     * bytes) and the watch ignores a schedule identical to the one it holds.
     *
     * The times are XTimePreference values, i.e. "HH:mm" strings, converted to
     * LOCAL minutes since midnight -- which is what the watch compares against
     * its own clock. Nothing here needs the current time.
     */
    private void addRadioSchedule(final TransactionBuilder builder) {
        final SharedPreferences prefs =
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final boolean enabled = prefs.getBoolean(F91KeplerConstants.PREF_SLEEP_ENABLED, false);
        final int start = F91KeplerProtocol.minutesFromHhMm(
                prefs.getString(F91KeplerConstants.PREF_SLEEP_START, "23:00"), 23 * 60);
        final int end = F91KeplerProtocol.minutesFromHhMm(
                prefs.getString(F91KeplerConstants.PREF_SLEEP_END, "07:00"), 7 * 60);
        builder.write(F91KeplerConstants.UUID_CHAR_RADIO_SCHED,
                      F91KeplerProtocol.radioSchedule(enabled, start, end));
    }

    /**
     * Read a numeric string preference, falling back to {@code def} on absence or
     * garbage. Shared by the mode positions and the brightness step -- both are
     * ListPreferences, i.e. stored as strings.
     */
    private static int modePos(final SharedPreferences prefs, final String key, final int def) {
        try {
            return Integer.parseInt(prefs.getString(key, Integer.toString(def)));
        } catch (final NumberFormatException e) {
            return def;
        }
    }
}
