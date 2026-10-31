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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerFirmware;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerImageCodec;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerImageStore;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerModes;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerTransfer;
import nodomain.freeyourgadget.gadgetbridge.externalevents.NotificationListener;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
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
 *       {@link #onReboot} / {@link #onFactoryReset} → reboot / clear bonds</li>
 *   <li>{@link #onSendConfiguration} → 12/24h time mode, DST flag, mode order,
 *       display brightness, sleep window, image upload, and on firmware 3.1 the
 *       UiOptions switches and counter names</li>
 *   <li>firmware 3.1: {@link #onSetAlarms} → five Record ALARM slots;
 *       {@link #onSendWeather} → also the 7-day forecast and sunrise/sunset</li>
 * </ul>
 */
public class F91KeplerSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerSupport.class);

    /** Per watch address, so it outlives a support object re-created for a reconnect. */
    private static final Map<String, F91KeplerBondWatch> BOND_WATCHES = new ConcurrentHashMap<>();

    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private final GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
    private final BatteryInfoProfile<F91KeplerSupport> batteryInfoProfile;
    private final DeviceInfoProfile<F91KeplerSupport> deviceInfoProfile;
    private final F91KeplerNotificationTracker notificationTracker = new F91KeplerNotificationTracker();
    // Ids that came from NotificationListener, the only source
    // whose removals can be checked again after a disconnect (an SMS from
    // SMSReceiver is never removed by anything, so it must not be dropped as
    // "gone from the phone").
    private final Set<Integer> listenerNotificationIds = ConcurrentHashMap.newKeySet();

    /** Recent notifications for the watch's Notifications mode, newest first,
     *  capped at the firmware's ring size. GB owns the active set; the watch is
     *  told the current top-N (so dismissals are reflected). */
    private static final int F91_RECENT_MAX = 5;

    /** Upload attempts per reconciliation round: the first try plus one retry. */
    private static final int IMAGE_MAX_ATTEMPTS = 2;
    /** Uploads spent on the current frame; reset once the watch confirms it.
     *  Touched from both the service thread and the GATT callback thread. */
    private volatile int imageAttempts;
    /** The connect check's read is on its way: its answer uploads the stored
     *  frame if needed, so a Send meanwhile must not start a second upload. */
    private volatile boolean imageCheckPending;
    /** Progress, done and failed for every transfer to the watch. */
    private final F91KeplerTransfers transfers = new F91KeplerTransfers(this);

    /**
     * Firmware 3.1 alarm read-back: the five slots as the watch holds them,
     * filled by {@link #onCharacteristicRead}. {@code alarmSetGen} counts the
     * phone's own alarm writes, so a read that raced a user edit is discarded
     * instead of overruling it.
     */
    private final int[][] alarmReadSlots = new int[F91KeplerConstants.ALARM_SLOTS_31][];
    private volatile int alarmSetGen;
    private volatile int alarmReadGen = -1;
    private volatile boolean alarmReadWrite;
    /**
     * Firmware 3.2 SOUND_CFG as last read from this watch (6 or 8 bytes, no
     * CRC), null until read. The sound switches are written as a change of
     * these bytes, so what the companion app or the watch set stays.
     */
    private volatile byte[] soundCfg;
    /** SOUND_CFG select + reads queued and not answered yet (guarded by soundWriteKeys). */
    private int soundReadsPending;
    /** A SOUND_CFG select + read is on the air right now: set when its transaction starts. */
    private volatile boolean soundInFlight;
    /** The five ALARM_SOUND reads (snooze length) are on the air; their payloads so far. */
    private volatile boolean alarmSoundInFlight;
    private final byte[][] alarmSounds = new byte[F91KeplerConstants.ALARM_SLOTS_31][];
    private int alarmSoundsGot;
    /** The connect read adopts the watch's values into the switches; later reads only refresh. */
    private volatile boolean soundAdopt;
    /** The switches to write once the fresh SOUND_CFG arrives (empty: just adopt the watch's). */
    private final java.util.Set<String> soundWriteKeys = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** Firmware version read over DIS on THIS connection (null until then).
     *  Every 3.1 write is gated on it, not on the cached device version: a
     *  watch reflashed to older firmware since the last connect must never be
     *  sent a characteristic it no longer has. */
    private volatile String connectedFw;

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
        // A new connection: forget the previous one's firmware version, so the
        // 3.1 gate (has31) is closed until THIS watch has reported its version.
        connectedFw = null;
        soundCfg = null;
        synchronized (soundWriteKeys) {
            soundReadsPending = 0;
            // Changes still waiting (a deferred read, a dropped link): the next connect writes them.
            addSoundDirty(GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()), soundWriteKeys);
            soundWriteKeys.clear();
        }
        soundInFlight = false;
        // A snooze change still to send keeps its dirty flag (set on the change, cleared
        // only after all five writes); a read that only showed the watch's value marks nothing.
        alarmSoundInFlight = false;
        soundAdopt = false;
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        if (GBApplication.getPrefs().syncTime()) {
            transfers.add(builder, F91KeplerTransfer.TIME, false, this::addSetTime);
        }
        // The sleep window is re-pushed on every connect (issue #213): a
        // reflashed watch or a wiped SNV would otherwise spend the night
        // reachable when it was asked to be quiet, and the user would have to
        // notice and toggle the setting to fix it.
        transfers.add(builder, F91KeplerTransfer.SETTINGS, false, b -> {
            addTimeMode(b);
            addDst(b);
            addRadioSchedule(b);
        });
        deviceInfoProfile.requestDeviceInfo(builder);
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        // The encrypted writes above went through: the bond is good.
        builder.run(this::onLinkInitialized);
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
        transfers.add(builder, F91KeplerTransfer.WEATHER, false, this::addWeather);
        // Likewise re-push the notification history (also volatile on the watch),
        // and the face's notification mark with it: a notification dismissed on
        // the phone while the link was down would otherwise leave the mark lit.
        // GB drops removals for a device that is not connected, so first forget
        // the notifications the phone no longer shows.
        dropInactiveNotifications();
        transfers.add(builder, F91KeplerTransfer.NOTIFICATIONS, false, this::addNotificationState);
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
        if (ev == F91KeplerConstants.ALERT_EVENT_ALARM && has31()) {
            readAlarmSlots(false);     // a one-shot just switched itself off
        }
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

    private F91KeplerBondWatch bondWatch() {
        return BOND_WATCHES.computeIfAbsent(getDevice().getAddress(), a -> new F91KeplerBondWatch());
    }

    /** Delay between checks on a link stuck after a security failure. */
    private static final long STUCK_CHECK_MS = 5_000;
    /** Checks (2 min) before the link is dropped even while Android still says pairing. */
    private static final int STUCK_CHECKS_MAX = 24;
    /**
     * Longest wait before dropping a link without a bond. Each such drop
     * reconnects and Android asks to pair again, so the wait doubles up to this
     * to keep an unanswered pairing dialog from popping up every few seconds.
     */
    private static final long STUCK_UNBONDED_MAX_MS = 5 * 60_000;
    private final Handler stuckHandler = new Handler(Looper.getMainLooper());
    /** Links dropped without a bond since the last initialisation. */
    private int unbondedDrops;

    /** See {@link F91KeplerBondWatch#stuckLink}. */
    private void watchStuckLink(final BluetoothGatt gatt) {
        stuckHandler.removeCallbacksAndMessages(null);
        final long firstDelay = Math.min(STUCK_CHECK_MS << Math.min(unbondedDrops, 6), STUCK_UNBONDED_MAX_MS);
        stuckHandler.postDelayed(new Runnable() {
            private int checks;

            @Override
            public void run() {
                final int bondState = gatt.getDevice().getBondState();
                checks++;
                final boolean bonding = bondState == BluetoothDevice.BOND_BONDING && checks < STUCK_CHECKS_MAX;
                switch (bondWatch().stuckLink(bonding)) {
                    case WAIT:
                        stuckHandler.postDelayed(this, STUCK_CHECK_MS);
                        break;
                    case DROP:
                        if (bondState != BluetoothDevice.BOND_BONDED) {
                            unbondedDrops++;
                        }
                        LOG.warn("F91 link failed the security step and did not end: disconnecting so it reconnects");
                        gatt.disconnect();
                        break;
                    default:
                        break;
                }
            }
        }, firstDelay);
    }

    @Override
    public void dispose() {
        stuckHandler.removeCallbacksAndMessages(null);
        transfers.dispose();
        super.dispose();
    }

    private void onLinkInitialized() {
        unbondedDrops = 0;
        bondWatch().onInitialized();
        F91KeplerPairAgainReceiver.cancelNotification(getContext());
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            bondWatch().onLinkUp();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            stuckHandler.removeCallbacksAndMessages(null);
            // The queue is dropped without write callbacks, so nothing else would
            // end a transfer that was on the air.
            transfers.onLinkLost();
            imageCheckPending = false;
            final boolean bonded = gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED;
            final boolean offer = bondWatch().onLinkDown(status, bonded);
            LOG.info("F91 link down: status {} bonded {} failed links in a row {}",
                    status, bonded, bondWatch().failedLinks());
            if (offer) {
                LOG.warn("F91 watch seems to have lost its keys for this phone: offering to pair again");
                F91KeplerPairAgainReceiver.showNotification(getContext(), getDevice());
            }
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
        if (status != BluetoothGatt.GATT_SUCCESS && bondWatch().onOperationStatus(status)) {
            watchStuckLink(gatt);
        }
        if (F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL.equals(characteristic.getUuid())) {
            reconcileImage(status == BluetoothGatt.GATT_SUCCESS ? value : null);
            return true;
        }
        if (F91KeplerConstants.UUID_CHAR_RECORD.equals(characteristic.getUuid()) && alarmSoundInFlight) {
            final boolean ours = status != BluetoothGatt.GATT_SUCCESS
                    || (value != null && value.length > 0 && value[0] == F91KeplerConstants.REC_ALARM_SOUND);
            // Another record's answer: the alarm-sound read was dropped without a callback.
            onAlarmSoundRead(ours && status == BluetoothGatt.GATT_SUCCESS ? value : null);
            if (ours) {
                return true;
            }
        }
        if (F91KeplerConstants.UUID_CHAR_RECORD.equals(characteristic.getUuid()) && soundInFlight) {
            // The answer of the sound read on the air (transactions run one at a time).
            endSoundRead();
            onSoundCfgRead(status == BluetoothGatt.GATT_SUCCESS ? F91KeplerProtocol.soundCfg(value) : null);
            return true;
        }
        if (F91KeplerConstants.UUID_CHAR_RECORD.equals(characteristic.getUuid())
                && alarmReadGen >= 0) {
            onAlarmSlotRead(status == BluetoothGatt.GATT_SUCCESS ? value : null);
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
        if (status != BluetoothGatt.GATT_SUCCESS && bondWatch().onOperationStatus(status)) {
            watchStuckLink(gatt);
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            transfers.onOperationFailed();
        }
        if (status != BluetoothGatt.GATT_SUCCESS && characteristic != null
                && (F91KeplerConstants.UUID_CHAR_IMAGE_CHUNK.equals(characteristic.getUuid())
                    || F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL.equals(characteristic.getUuid()))) {
            LOG.warn("F91 image write to {} failed with status {}", characteristic.getUuid(), status);
            reconcileImage(null);
        }
        if (status != BluetoothGatt.GATT_SUCCESS && characteristic != null
                && F91KeplerConstants.UUID_CHAR_RECORD.equals(characteristic.getUuid())
                && alarmSoundInFlight) {
            LOG.warn("F91 alarm sound select failed with status {}", status);
            onAlarmSoundRead(null);
            return super.onCharacteristicWrite(gatt, characteristic, status);
        }
        if (status != BluetoothGatt.GATT_SUCCESS && characteristic != null
                && F91KeplerConstants.UUID_CHAR_RECORD.equals(characteristic.getUuid())
                && soundInFlight) {
            // Our select failed; its read was cancelled with the rest of the transaction.
            LOG.warn("F91 sound settings select failed with status {}", status);
            endSoundRead();
            onSoundCfgRead(null);
            return super.onCharacteristicWrite(gatt, characteristic, status);
        }
        if (status != BluetoothGatt.GATT_SUCCESS && characteristic != null
                && F91KeplerConstants.UUID_CHAR_RECORD.equals(characteristic.getUuid())
                && alarmReadGen >= 0) {
            // A failed select cancels the reads queued behind it, so no read
            // answer would ever end the round: end it here, writing as before.
            LOG.warn("F91 alarm slot select failed with status {}", status);
            finishAlarmRead(false);
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
        connectedFw = fw;
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
        transfers.queue("restore phone-owned config", F91KeplerTransfer.SETTINGS, false, builder -> {
            if (F91KeplerFirmware.atLeast(fw, F91KeplerFirmware.MIN_MODE_ORDER_10)) {
                addModeOrder(builder);
            }
            if (prefs.getBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, false)
                    && F91KeplerFirmware.atLeast(fw, F91KeplerFirmware.MIN_BRIGHTNESS)) {
                addBrightness(builder);
                // Cleared once the watch took it, so a failed write tries again next connect.
                builder.run(() -> prefs.edit().putBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, false).apply());
            }
        });
        if (F91KeplerFirmware.has31(fw)) {
            // Its own transaction: a refused 3.1 write must not cancel the
            // restores above. All of it is phone-owned and cheap to repeat --
            // the watch skips the flash write for an unchanged value.
            transfers.queue("restore 3.1 config", F91KeplerTransfer.SETTINGS, false, b31 -> {
                addUiOptions(b31);
                addCounterNames(b31);
                addForecastAndSun(b31);
            });
            if (F91KeplerFirmware.has32(fw)) {
                soundAdopt = true;
                readSoundCfg();             // before the alarm reads
                // Changed while away: written; else the watch's snooze is shown.
                readAlarmSounds();
            }
            // The slots last, and only after reading them: a one-shot that rang
            // while the phone was away is off on the watch, and writing the
            // app's list blindly would re-arm it for tomorrow.
            readAlarmSlots(true);
        }
    }

    // --- Sound (firmware 3.2) ------------------------------------------------

    private boolean has32() {
        return F91KeplerFirmware.has32(connectedFw);
    }

    private boolean soundReadDue() {
        synchronized (soundWriteKeys) {
            return soundReadsPending > 0;
        }
    }

    // --- Snooze length (ALARM_SOUND, firmware 3.2) ----------------------------

    private void markSnoozeDirty() {
        GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()).edit()
                .putBoolean(F91KeplerConstants.PREF_SOUND_SNOOZE_DIRTY, true).apply();
    }

    /** Select + read the five ALARM_SOUND records; onAlarmSoundRead writes the snooze back. */
    private void readAlarmSounds() {
        if (alarmSoundInFlight) {
            return;
        }
        final TransactionBuilder builder = createTransactionBuilder("read alarm sounds");
        builder.run(() -> {
            synchronized (alarmSounds) {
                java.util.Arrays.fill(alarmSounds, null);
                alarmSoundsGot = 0;
            }
            alarmSoundInFlight = true;
        });
        for (int i = 0; i < F91KeplerConstants.ALARM_SLOTS_31; i++) {
            builder.write(F91KeplerConstants.UUID_CHAR_RECORD,
                          F91KeplerProtocol.record(F91KeplerConstants.REC_ALARM_SOUND, i, new byte[0]));
            builder.read(F91KeplerConstants.UUID_CHAR_RECORD);
        }
        builder.queue();
    }

    /**
     * One ALARM_SOUND answer (null: failed). After all five, each alarm is written back with
     * the chosen snooze and its own tone and escalation. A failure keeps the change for the
     * next connect.
     */
    private void onAlarmSoundRead(final byte[] value) {
        final byte[][] got;
        synchronized (alarmSounds) {
            final byte[] p = F91KeplerProtocol.alarmSound(value, alarmSoundsGot);
            if (p == null) {
                alarmSoundInFlight = false;
                LOG.warn("F91 alarm sound read failed; a pending snooze change goes after the next connect");
                return;
            }
            alarmSounds[alarmSoundsGot++] = p;
            if (alarmSoundsGot < alarmSounds.length) {
                return;
            }
            alarmSoundInFlight = false;
            got = alarmSounds.clone();
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if (!prefs.getBoolean(F91KeplerConstants.PREF_SOUND_SNOOZE_DIRTY, false)) {
            // Nothing to write: show the watch's snooze when all five alarms share it
            // (the companion app can set them one by one).
            final int first = got[0][1] & 0xFF;
            boolean same = true;
            for (final byte[] p : got) {
                same &= (p[1] & 0xFF) == first;
            }
            if (same && (first == 0 || first == 5 || first == 9 || first == 15)) {   // the list's values
                evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences(
                        F91KeplerConstants.PREF_SOUND_SNOOZE, String.valueOf(first)));
            }
            return;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(prefs.getString(F91KeplerConstants.PREF_SOUND_SNOOZE, "9"));
        } catch (final NumberFormatException e) {
            parsed = 9;
        }
        final int minutes = parsed;
        transfers.queue("set snooze length", F91KeplerTransfer.SOUNDS, true, builder -> {
            for (int i = 0; i < got.length; i++) {
                builder.write(F91KeplerConstants.UUID_CHAR_RECORD, F91KeplerProtocol.alarmSoundSnooze(i, got[i], minutes));
            }
            // Done only when all five went through: a failed write or a dropped link aborts
            // the transaction before this, and the change stays for the next connect.
            final String written = String.valueOf(minutes);
            builder.run(() -> {
                // Changed again while these went out: that change keeps the flag (its own read follows).
                if (written.equals(prefs.getString(F91KeplerConstants.PREF_SOUND_SNOOZE, "9"))) {
                    prefs.edit().putBoolean(F91KeplerConstants.PREF_SOUND_SNOOZE_DIRTY, false).apply();
                }
            });
        });
    }

    private void endSoundRead() {
        soundInFlight = false;
        synchronized (soundWriteKeys) {
            if (soundReadsPending > 0) {
                soundReadsPending--;
            }
        }
    }

    private void readSoundCfg() {
        synchronized (soundWriteKeys) {
            soundReadsPending++;
        }
        final TransactionBuilder builder = createTransactionBuilder("read sound settings");
        builder.run(() -> soundInFlight = true);
        builder.write(F91KeplerConstants.UUID_CHAR_RECORD,
                      F91KeplerProtocol.record(F91KeplerConstants.REC_SOUND_CFG, 0, new byte[0]));
        builder.read(F91KeplerConstants.UUID_CHAR_RECORD);
        builder.queue();
    }

    /**
     * The watch's sound settings. Normally they win: silent mode can be switched
     * on the watch, and the companion app may have set them. Only a switch the
     * user changed here while the watch was away is written back.
     */
    private void onSoundCfgRead(final byte[] cfg) {
        final java.util.List<String> keys;
        synchronized (soundWriteKeys) {
            keys = new java.util.ArrayList<>(soundWriteKeys);
            soundWriteKeys.clear();
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if (cfg == null) {
            LOG.warn("F91 sound settings read failed; the sound switches stay as they are");
            soundAdopt = false;
            // Not lost: the next 3.2 connect reads the watch and writes these.
            addSoundDirty(prefs, keys);
            return;
        }
        soundCfg = cfg;
        final boolean adopt = soundAdopt;
        soundAdopt = false;
        if (adopt) {
            // Only the switches changed here while away go back; the rest is the watch's.
            for (final String k : prefs.getStringSet(F91KeplerConstants.PREF_SOUND_DIRTY_KEYS,
                                                     java.util.Collections.emptySet())) {
                if (!keys.contains(k)) {
                    keys.add(k);
                }
            }
            prefs.edit().remove(F91KeplerConstants.PREF_SOUND_DIRTY_KEYS).apply();
        }
        if (adopt) {
            // The watch's values, except a switch the user is changing right now. Sent as an
            // event: it stores them and tells the settings screen, whose customizer
            // (onDeviceChanged) then shows them.
            final int f = F91KeplerProtocol.soundFlags(cfg);
            final GBDeviceEventUpdatePreferences ev = new GBDeviceEventUpdatePreferences();
            adoptSwitch(ev, keys, F91KeplerConstants.PREF_SOUND_MUTE, f, F91KeplerConstants.SND_SILENT);
            adoptSwitch(ev, keys, F91KeplerConstants.PREF_SOUND_CALL, f, F91KeplerConstants.SND_CALL);
            adoptSwitch(ev, keys, F91KeplerConstants.PREF_SOUND_TEXT, f, F91KeplerConstants.SND_TEXT);
            adoptSwitch(ev, keys, F91KeplerConstants.PREF_SOUND_LOWBATT, f, F91KeplerConstants.SND_LOWBATT);
            adoptSwitch(ev, keys, F91KeplerConstants.PREF_SOUND_LOST, f, F91KeplerConstants.SND_LOST);
            evaluateGBDeviceEvent(ev);
        }
        if (!keys.isEmpty()) {
            writeSoundSwitches(keys);       // changes while connected: only their own bits
        }
    }

    private static void adoptSwitch(final GBDeviceEventUpdatePreferences ev, final java.util.List<String> pending,
                                    final String key, final int flags, final int bit) {
        if (!pending.contains(key)) {
            ev.withPreference(key, (flags & bit) != 0);
        }
    }

    /** The given switches into the freshly read SOUND_CFG; every other bit stays the watch's. */
    private void writeSoundSwitches(final java.util.List<String> keys) {
        final byte[] cfg = soundCfg;
        if (cfg == null) {
            return;
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        int flags = F91KeplerProtocol.soundFlags(cfg);
        for (final String key : keys) {
            switch (key) {
                case F91KeplerConstants.PREF_SOUND_MUTE:
                    flags = F91KeplerProtocol.soundFlagWith(flags, F91KeplerConstants.SND_SILENT, prefs.getBoolean(key, false));
                    break;
                case F91KeplerConstants.PREF_SOUND_CALL:
                    flags = F91KeplerProtocol.soundFlagWith(flags, F91KeplerConstants.SND_CALL, prefs.getBoolean(key, true));
                    break;
                case F91KeplerConstants.PREF_SOUND_TEXT:
                    flags = F91KeplerProtocol.soundFlagWith(flags, F91KeplerConstants.SND_TEXT, prefs.getBoolean(key, true));
                    break;
                case F91KeplerConstants.PREF_SOUND_LOWBATT:
                    flags = F91KeplerProtocol.soundFlagWith(flags, F91KeplerConstants.SND_LOWBATT, prefs.getBoolean(key, false));
                    break;
                case F91KeplerConstants.PREF_SOUND_LOST:
                    if (F91KeplerFirmware.hasLostAlert(connectedFw)) {   // 3.2.1: false beeps after radio-off
                        flags = F91KeplerProtocol.soundFlagWith(flags, F91KeplerConstants.SND_LOST, prefs.getBoolean(key, false));
                    }
                    break;
                default:
                    break;
            }
        }
        writeSoundCfg(cfg, flags);
    }

    private static void addSoundDirty(final SharedPreferences prefs, final java.util.Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        final java.util.Set<String> dirty = new java.util.HashSet<>(
                prefs.getStringSet(F91KeplerConstants.PREF_SOUND_DIRTY_KEYS, java.util.Collections.emptySet()));
        dirty.addAll(keys);
        prefs.edit().putStringSet(F91KeplerConstants.PREF_SOUND_DIRTY_KEYS, dirty).apply();
    }

    private void writeSoundCfg(final byte[] cfg, final int flags) {
        final byte[] frame = F91KeplerProtocol.soundCfgWrite(cfg, flags);
        soundCfg = java.util.Arrays.copyOfRange(frame, 2, frame.length);
        transfers.queue("set sound switches", F91KeplerTransfer.SOUNDS, true,
                        builder -> builder.write(F91KeplerConstants.UUID_CHAR_RECORD, frame));
    }

    /** True when this connection's firmware is known to have the 3.1 surface. */
    private boolean has31() {
        return F91KeplerFirmware.has31(connectedFw);
    }

    /**
     * Text for the watch's 5x7 font, folded with the firmware's own table:
     * symbols without a look-alike pass through for the watch to box from
     * 3.1.14, and become '?' before it (older firmware draws stray pixels).
     */
    private String forWatch(final String text) {
        return F91KeplerText.fold(text, F91KeplerFirmware.atLeast(connectedFw, F91KeplerFirmware.MIN_TEXT_FOLD));
    }

    // --- Time ---------------------------------------------------------------

    @Override
    public void onSetTime() {
        transfers.queue("set time", F91KeplerTransfer.TIME, false, this::addSetTime);
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
        // A NotificationListener notification may already be gone again
        // (dismissed while a cached copy waited for the reconnect): then it
        // still pops up, but leaves no mark and no history entry behind.
        final boolean fromListener = NotificationListener.isPostedNotification(notificationSpec.getId());
        if (!fromListener || NotificationListener.isNotificationActive(notificationSpec.getId())) {
            notificationTracker.add(notificationSpec.getId(), category);
            addRecent(notificationSpec);
            if (fromListener) {
                listenerNotificationIds.add(notificationSpec.getId());
            }
        }

        final TransactionBuilder builder = createTransactionBuilder("notification");
        builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_BAR, notificationTracker.bitmask());

        if (isNotificationPopupEnabled()) {
            final String sender = StringUtils.firstNonBlank(
                    notificationSpec.getSender(), notificationSpec.getTitle(), notificationSpec.getSourceName());
            if (StringUtils.isNotBlank(sender)) {
                // Split-tile popup: send the app label (sourceName) + sender so the
                // watch can show "<app> / <sender> / TEXT". The serializer keeps the
                // whole payload within the characteristic's byte budget.
                final String app = StringUtils.firstNonBlank(notificationSpec.getSourceName(), "");
                builder.write(F91KeplerConstants.UUID_CHAR_INCOMING_TEXT,
                              F91KeplerProtocol.incomingTextPopup(forWatch(app), forWatch(sender)));
            }
        }
        addRecentNotifications(builder);   // push the updated history list to the watch
        builder.queue();
    }

    @Override
    public void onDeleteNotification(final int id) {
        notificationTracker.remove(id);
        removeRecent(id);
        listenerNotificationIds.remove(id);
        final TransactionBuilder builder = createTransactionBuilder("delete notification");
        builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_BAR, notificationTracker.bitmask());
        addRecentNotifications(builder);   // reflect the dismissal in the history list
        builder.queue();
    }

    // --- Notification history (Notifications mode) --------------------------

    private void addRecent(final NotificationSpec spec) {
        final String app = StringUtils.firstNonBlank(spec.getSourceName(), "");
        final String sender = StringUtils.firstNonBlank(spec.getSender(), spec.getTitle(), "notification");
        removeRecent(spec.getId());                    // de-dupe by id
        recent.add(0, new RecentNotif(spec.getId(), app, sender));   // newest first
        while (recent.size() > F91_RECENT_MAX) {
            recent.remove(recent.size() - 1);
        }
    }

    private void dropInactiveNotifications() {
        for (final Iterator<Integer> it = listenerNotificationIds.iterator(); it.hasNext(); ) {
            final int id = it.next();
            if (!NotificationListener.isNotificationActive(id)) {
                notificationTracker.remove(id);
                removeRecent(id);
                it.remove();
            }
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
    /** The face's notification mark and the history list, as the phone has them now. */
    private void addNotificationState(final TransactionBuilder builder) {
        builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_BAR, notificationTracker.bitmask());
        addRecentNotifications(builder);
    }

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
                          F91KeplerProtocol.notificationEntry(i, total, forWatch(e.app), forWatch(e.sender)));
        }
    }

    private static F91KeplerNotificationTracker.Category categorize(final NotificationSpec spec) {
        final NotificationType type = spec.getType();
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
        if (callSpec.getCommand() != CallSpec.CALL_INCOMING) {
            // The watch auto-clears the popup after ~5s; nothing to do on accept/end.
            return;
        }
        final String name = StringUtils.firstNonBlank(callSpec.getName(), callSpec.getNumber());
        final TransactionBuilder builder = createTransactionBuilder("incoming call");
        builder.write(F91KeplerConstants.UUID_CHAR_INCOMING_CALL, F91KeplerProtocol.contactName(forWatch(name)));
        builder.queue();
    }

    // --- Weather ------------------------------------------------------------

    @Override
    public void onSendWeather() {
        sendWeather(false);
    }

    private void sendWeather(final boolean byUser) {
        transfers.queue("send weather", F91KeplerTransfer.WEATHER, byUser, builder -> {
            addWeather(builder);
            if (has31()) {
                addForecastAndSun(builder);
            }
        });
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
        final int temp = F91KeplerProtocol.tempInUnit(weatherSpec.getCurrentTemp(), useFahrenheit());
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
        if (has31()) {
            alarmSetGen++;
            transfers.queue("set alarm slots", F91KeplerTransfer.ALARMS, true, builder -> addAlarmSlots(builder, alarms));
            return;
        }
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

        final Calendar armed = soonest;
        transfers.queue("set alarms", F91KeplerTransfer.ALARMS, true, builder -> {
            if (armed == null) {
                builder.write(F91KeplerConstants.UUID_CHAR_ALARM_ENABLED, F91KeplerProtocol.alarmEnabled(false));
                builder.write(F91KeplerConstants.UUID_CHAR_ALARM_TIME, F91KeplerProtocol.alarmTime(0L));
            } else {
                // Write the time first, then enable: the firmware only arms when
                // AlarmEnabled && AlarmTime != 0. getTimeInMillis() is UTC, matching
                // the watch's UTC clock.
                final long epochSeconds = armed.getTimeInMillis() / 1000L;
                builder.write(F91KeplerConstants.UUID_CHAR_ALARM_TIME, F91KeplerProtocol.alarmTime(epochSeconds));
                builder.write(F91KeplerConstants.UUID_CHAR_ALARM_ENABLED, F91KeplerProtocol.alarmEnabled(true));
                LOG.debug("F91 alarm armed for {} (epoch {})", armed.getTime(), epochSeconds);
            }
        });
    }

    /**
     * Firmware 3.1: the five Record ALARM slots, by Gadgetbridge alarm position.
     * Every slot is written -- an absent or unused alarm as disabled -- so a slot
     * removed on the phone cannot keep ringing on the watch. The legacy one-shot
     * alarm (B2F5/B2F6) is switched off in the same breath: an alarm set by an
     * older Gadgetbridge would otherwise ring alongside the slots.
     */
    private void addAlarmSlots(final TransactionBuilder builder, final List<? extends Alarm> alarms) {
        final Alarm[] bySlot = new Alarm[F91KeplerConstants.ALARM_SLOTS_31];
        for (final Alarm a : alarms) {
            if (a != null && a.getPosition() >= 0 && a.getPosition() < bySlot.length) {
                bySlot[a.getPosition()] = a;
            }
        }
        for (int i = 0; i < bySlot.length; i++) {
            final Alarm a = bySlot[i];
            final boolean on = a != null && a.getEnabled() && !a.getUnused();
            final byte[] slot = a == null
                    ? F91KeplerProtocol.alarmSlot(false, 0, 0, 0)
                    : F91KeplerProtocol.alarmSlot(on, a.getHour(), a.getMinute(), a.getRepetition());
            builder.write(F91KeplerConstants.UUID_CHAR_RECORD,
                          F91KeplerProtocol.record(F91KeplerConstants.REC_ALARM, i, slot));
        }
        builder.write(F91KeplerConstants.UUID_CHAR_ALARM_ENABLED, F91KeplerProtocol.alarmEnabled(false));
        builder.write(F91KeplerConstants.UUID_CHAR_ALARM_TIME, F91KeplerProtocol.alarmTime(0L));
    }

    /**
     * Select and read the five ALARM records (select = {@code [type][index]}
     * with no payload). The answers arrive in {@link #onAlarmSlotRead}, in
     * order. {@code thenWrite}: write the app's slots once all five are in (the
     * connect path); otherwise only reconcile (after the watch rang one).
     */
    private void readAlarmSlots(final boolean thenWrite) {
        java.util.Arrays.fill(alarmReadSlots, null);
        alarmReadWrite = thenWrite;
        alarmReadGen = alarmSetGen;
        final TransactionBuilder builder = createTransactionBuilder("read alarm slots");
        // Transactions run one at a time: a sound read still "on the air" here was
        // dropped without a callback (Android refused it locally). Its change waits.
        builder.run(() -> {
            if (soundInFlight) {
                endSoundRead();
                onSoundCfgRead(null);
            }
            if (alarmSoundInFlight) {
                onAlarmSoundRead(null);
            }
        });
        for (int i = 0; i < F91KeplerConstants.ALARM_SLOTS_31; i++) {
            builder.write(F91KeplerConstants.UUID_CHAR_RECORD,
                          F91KeplerProtocol.record(F91KeplerConstants.REC_ALARM, i, new byte[0]));
            builder.read(F91KeplerConstants.UUID_CHAR_RECORD);
        }
        builder.queue();
    }

    /**
     * One ALARM record answer. A failed or unparseable read ends the round:
     * the slots are written from the app's list exactly as before this
     * read-back existed, so a watch that cannot answer loses nothing.
     */
    private void onAlarmSlotRead(final byte[] value) {
        final int[] slot = F91KeplerProtocol.alarmRecord(value);
        if (slot == null || slot[0] >= alarmReadSlots.length) {
            LOG.warn("F91 alarm slot read failed, writing the app's alarms as they are");
            finishAlarmRead(false);
            return;
        }
        alarmReadSlots[slot[0]] = slot;
        if (slot[0] == alarmReadSlots.length - 1) {
            finishAlarmRead(true);
        }
    }

    private void finishAlarmRead(final boolean complete) {
        final boolean write = alarmReadWrite;
        final boolean raced = alarmReadGen != alarmSetGen;
        alarmReadGen = -1;
        // A sound switch changed during the read-back waited for the select register.
        if (!soundWriteKeys.isEmpty() && !soundReadDue()) {
            readSoundCfg();
        }
        if (has32() && GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getBoolean(F91KeplerConstants.PREF_SOUND_SNOOZE_DIRTY, false)) {
            readAlarmSounds();
        }
        if (raced) {
            LOG.debug("F91 alarms were edited during the read-back, keeping the edit");
            return;                    // onSetAlarms has written the user's list already
        }
        if (complete) {
            reconcileOneShots();
        }
        if (write) {
            transfers.queue("restore alarm slots", F91KeplerTransfer.ALARMS, false,
                    builder -> addAlarmSlots(builder, DBHelper.getAlarms(getDevice())));
        }
    }

    /** One-shots the watch has rung are switched off in the app too. */
    private void reconcileOneShots() {
        int changed = 0;
        for (final nodomain.freeyourgadget.gadgetbridge.entities.Alarm a : DBHelper.getAlarms(getDevice())) {
            final int pos = a.getPosition();
            if (pos < 0 || pos >= alarmReadSlots.length) {
                continue;
            }
            if (F91KeplerProtocol.watchRangOneShot(a.getEnabled(), a.getUnused(), a.getHour(),
                                                   a.getMinute(), a.getRepetition(),
                                                   alarmReadSlots[pos])) {
                LOG.info("F91 one-shot alarm {} rang on the watch, switching it off", pos);
                a.setEnabled(false);
                DBHelper.store(a);
                changed++;
            }
        }
        if (changed > 0) {
            LocalBroadcastManager.getInstance(getContext())
                    .sendBroadcast(new Intent(DeviceService.ACTION_SAVE_ALARMS));
        }
    }

    // --- Firmware 3.1: options, counters, forecast, sun --------------------

    /** UiOptions (F2F3) from the four 3.1 preferences. */
    private void addUiOptions(final TransactionBuilder builder) {
        final SharedPreferences prefs =
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        final int bits = F91KeplerProtocol.uiOptionBits(
                prefs.getBoolean(F91KeplerConstants.PREF_WEEKDAY, false),
                prefs.getString(F91KeplerConstants.PREF_WEEKDAY_LANG, "auto"),
                Locale.getDefault(),
                prefs.getBoolean(F91KeplerConstants.PREF_QUIET_TEXT, false),
                prefs.getBoolean(F91KeplerConstants.PREF_HOURLY_CHIME, false));
        builder.write(F91KeplerConstants.UUID_CHAR_UI_OPTIONS, F91KeplerProtocol.uiOptions(bits));
    }

    /** All three counter names; the counts stay the watch's (see counterName). */
    private void addCounterNames(final TransactionBuilder builder) {
        for (int i = 0; i < F91KeplerConstants.COUNTERS; i++) {
            addCounterName(builder, i);
        }
    }

    private void addCounterName(final TransactionBuilder builder, final int index) {
        final String name = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress())
                .getString(F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + index, "");
        builder.write(F91KeplerConstants.UUID_CHAR_RECORD,
                      F91KeplerProtocol.record(F91KeplerConstants.REC_COUNTER, index,
                                               F91KeplerProtocol.counterName(name)));
    }

    /**
     * The 7-day forecast and today's sunrise/sunset (Record FORECAST, SUN). Both
     * are RAM-only on the watch, so they ride along with every weather push and
     * every 3.1 connect. Day 0 is today (GB keeps today apart from its forecast
     * list, which starts tomorrow). Days GB has no forecast for are not sent: the
     * watch shows "--" for them. No-op without weather.
     */
    private void addForecastAndSun(final TransactionBuilder builder) {
        final WeatherSpec w = Weather.getWeatherSpec();
        if (w == null) {
            return;
        }
        final boolean f = useFahrenheit();
        builder.write(F91KeplerConstants.UUID_CHAR_RECORD, F91KeplerProtocol.record(
                F91KeplerConstants.REC_FORECAST, 0, F91KeplerProtocol.forecastDay(
                        F91KeplerProtocol.owmToCondition(w.getCurrentConditionCode()),
                        F91KeplerProtocol.tempInUnit(w.getTodayMaxTemp(), f),
                        F91KeplerProtocol.tempInUnit(w.getTodayMinTemp(), f))));
        final List<WeatherSpec.Daily> days = w.getForecasts();
        for (int d = 1; d < F91KeplerConstants.FORECAST_DAYS && d - 1 < days.size(); d++) {
            final WeatherSpec.Daily day = days.get(d - 1);
            builder.write(F91KeplerConstants.UUID_CHAR_RECORD, F91KeplerProtocol.record(
                    F91KeplerConstants.REC_FORECAST, d, F91KeplerProtocol.forecastDay(
                            F91KeplerProtocol.owmToCondition(day.getConditionCode()),
                            F91KeplerProtocol.tempInUnit(day.getMaxTemp(), f),
                            F91KeplerProtocol.tempInUnit(day.getMinTemp(), f))));
        }
        final TimeZone tz = TimeZone.getDefault();
        final Calendar today = Calendar.getInstance(tz);
        builder.write(F91KeplerConstants.UUID_CHAR_RECORD, F91KeplerProtocol.record(
                F91KeplerConstants.REC_SUN, 0, F91KeplerProtocol.sunTimes(
                        F91KeplerProtocol.sunMinute(w.getSunRise(), today, tz),
                        F91KeplerProtocol.sunMinute(w.getSunSet(), today, tz))));
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
    public void onReboot() {
        // Debug screen "Reboot": 0x01, a deferred reset.
        final TransactionBuilder builder = createTransactionBuilder("reset");
        builder.write(F91KeplerConstants.UUID_CHAR_DEVICE_COMMAND, F91KeplerConstants.CMD_RESET);
        builder.queue();
    }

    @Override
    public void onFactoryReset() {
        // Debug screen "Factory reset": 0x16 erases every bond on the watch and
        // reboots. After it the phone still holds its side of the bond and must
        // forget the watch in Android's Bluetooth settings before pairing again
        // -- that is why the ordinary UI never offers it.
        final TransactionBuilder builder = createTransactionBuilder("factory reset");
        builder.write(F91KeplerConstants.UUID_CHAR_DEVICE_COMMAND, F91KeplerConstants.CMD_CLEAR_BONDS);
        builder.queue();
    }

    // --- Image (Image mode) -------------------------------------------------

    /**
     * Ask the watch what image it holds, but only when we have one to compare
     * against. The answer arrives in {@link #onCharacteristicRead}.
     */
    private void addImageCheck(final TransactionBuilder builder) {
        imageAttempts = 0;
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        if (prefs.getBoolean(F91KeplerConstants.PREF_IMAGE_GAVE_UP, false)
                || F91KeplerImageStore.load(getDevice()) == null) {
            return;
        }
        // Set and cleared by the transaction itself: a connect write refused before
        // it (the queue then skips the rest), or a watch without the characteristic
        // (no read is added), must not leave Send waiting for an answer that never comes.
        builder.run(() -> imageCheckPending = true);
        builder.read(F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL);
        builder.run(() -> imageCheckPending = false);
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
        imageCheckPending = false;
        final byte[] frame = F91KeplerImageStore.load(getDevice());
        if (frame == null) {
            imageAttempts = 0;
            return;
        }
        if (F91KeplerProtocol.imageControlMatches(control, F91KeplerImageCodec.xor8(frame))) {
            if (imageAttempts > 0) {
                // The read that trails an upload: the watch latched what we sent.
                LOG.debug("F91 image upload confirmed by the watch (attempt {})", imageAttempts);
            } else {
                LOG.debug("F91 watch already holds the stored image, skipping the upload");
            }
            imageAttempts = 0;
            // Also when it was already there: a screen waiting for the watch learns it is done.
            transfers.finish(F91KeplerTransfer.IMAGE, true);
            return;
        }
        if (imageAttempts >= IMAGE_MAX_ATTEMPTS) {
            LOG.warn("F91 image not confirmed after {} upload attempts, giving up", imageAttempts);
            imageAttempts = 0;
            GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()).edit()
                    .putBoolean(F91KeplerConstants.PREF_IMAGE_GAVE_UP, true).apply();
            transfers.finish(F91KeplerTransfer.IMAGE, false);
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
        if (getCharacteristic(F91KeplerConstants.UUID_CHAR_IMAGE_CHUNK) == null) {
            // Firmware without Image mode: the writes would be dropped and nothing would answer.
            LOG.warn("F91 watch has no image characteristic, not uploading");
            transfers.finish(F91KeplerTransfer.IMAGE, false);
            return;
        }
        transfers.queue("send image", F91KeplerTransfer.IMAGE, true, builder -> {
            builder.write(F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL, F91KeplerProtocol.imageBegin());
            for (final byte[] chunk : F91KeplerProtocol.imageChunks(frame)) {
                builder.write(F91KeplerConstants.UUID_CHAR_IMAGE_CHUNK, chunk);
            }
            builder.write(F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL,
                          F91KeplerProtocol.imageCommit(F91KeplerImageCodec.xor8(frame)));
            builder.read(F91KeplerConstants.UUID_CHAR_IMAGE_CONTROL);
        });
    }

    // --- Settings -----------------------------------------------------------

    @Override
    public void onSendConfiguration(final String config) {
        super.onSendConfiguration(config);
        switch (config) {
            case DeviceSettingsPreferenceConst.PREF_TIMEFORMAT:
                transfers.queue("set time mode", F91KeplerTransfer.SETTINGS, true, this::addTimeMode);
                break;
            case F91KeplerModes.PREF_MODES:
            case F91KeplerModes.PREF_MODES_31: {
                transfers.queue("set mode order", F91KeplerTransfer.SETTINGS, true, builder -> addModeOrder(builder));
                warnIfModesDropped();
                break;
            }
            case F91KeplerConstants.PREF_WEEKDAY:
            case F91KeplerConstants.PREF_WEEKDAY_LANG:
            case F91KeplerConstants.PREF_QUIET_TEXT:
            case F91KeplerConstants.PREF_HOURLY_CHIME: {
                // Away or pre-3.1: nothing to do now -- the next 3.1 connect
                // re-pushes every option (restorePhoneOwnedConfig).
                if (has31()) {
                    transfers.queue("set ui options", F91KeplerTransfer.SETTINGS, true, builder -> addUiOptions(builder));
                }
                break;
            }
            case F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "0":
            case F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "1":
            case F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "2": {
                if (has31()) {
                    transfers.queue("set counter name", F91KeplerTransfer.SETTINGS, true,
                            builder -> addCounterName(builder, config.charAt(config.length() - 1) - '0'));
                }
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
                // Dirty until the watch took it: a failed write is then sent by Try
                // again or the next connect, like a change made while away.
                final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
                prefs.edit().putBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, true).apply();
                transfers.queue("set brightness", F91KeplerTransfer.SETTINGS, true, builder -> {
                    addBrightness(builder);
                    builder.run(() -> prefs.edit().putBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, false).apply());
                });
                break;
            }
            case F91KeplerConstants.PREF_SLEEP_ENABLED:
            case F91KeplerConstants.PREF_SLEEP_START:
            case F91KeplerConstants.PREF_SLEEP_END: {
                transfers.queue("set sleep window", F91KeplerTransfer.SETTINGS, true, builder -> addRadioSchedule(builder));
                break;
            }
            case F91KeplerConstants.PREF_SOUND_LOST:
                if (getDevice().isInitialized() && !F91KeplerFirmware.hasLostAlert(connectedFw)) {
                    break;                  // 3.2.1 could beep falsely after a radio-off night
                }
                // fall through
            case F91KeplerConstants.PREF_SOUND_MUTE:
            case F91KeplerConstants.PREF_SOUND_CALL:
            case F91KeplerConstants.PREF_SOUND_TEXT:
            case F91KeplerConstants.PREF_SOUND_LOWBATT: {
                // connectedFw outlives a disconnect (it is cleared in initializeDevice),
                // so a pending auto-reconnect must not count as connected.
                if (getDevice().isInitialized() && has32()) {
                    // Read first: silent mode may have been switched on the watch, or
                    // quiet hours in the companion app, since this connect.
                    soundWriteKeys.add(config);
                    // An alarm read-back holds the select register: finishAlarmRead reads then.
                    if (alarmReadGen < 0) {
                        readSoundCfg();
                    }
                } else {
                    // Away (or not read yet): the next 3.2 connect reads the watch, then writes this.
                    addSoundDirty(GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()),
                                  java.util.Collections.singletonList(config));
                }
                break;
            }
            case F91KeplerConstants.PREF_SOUND_SNOOZE: {
                // Away, reconnecting, or an alarm read-back holds the select register:
                // finishAlarmRead / the next connect reads and writes it.
                markSnoozeDirty();
                if (getDevice().isInitialized() && has32() && alarmReadGen < 0) {
                    readAlarmSounds();
                }
                break;
            }
            case F91KeplerConstants.PREF_SOUND_TEST: {
                if (getDevice().isInitialized() && has32()) {
                    final TransactionBuilder builder = createTransactionBuilder("test sound");
                    builder.write(F91KeplerConstants.UUID_CHAR_RECORD,
                                  F91KeplerProtocol.soundPlay(F91KeplerConstants.TONE_BEEP));
                    builder.queue();
                }
                break;
            }
            case F91KeplerConstants.PREF_IMAGE_UPLOAD:
                sendStoredImage();
                break;
            default:
                if (config.startsWith(F91KeplerConstants.PREF_TRANSFER_RETRY_PREFIX)) {
                    retryTransfer(config.substring(F91KeplerConstants.PREF_TRANSFER_RETRY_PREFIX.length()));
                }
                break;
        }
    }

    /**
     * The activity has already stored the packed frame; this is "send it now",
     * and the first of the two attempts. Also Try again after a failed upload.
     */
    private void sendStoredImage() {
        final byte[] frame = F91KeplerImageStore.load(getDevice());
        if (frame == null) {
            LOG.warn("F91 image upload requested but no frame is stored");
            return;
        }
        // An explicit send is also the way out of a given-up round.
        GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress()).edit()
                .putBoolean(F91KeplerConstants.PREF_IMAGE_GAVE_UP, false).apply();
        if (!getDevice().isInitialized()) {
            // The connect check sends the stored frame.
            transfers.waiting(F91KeplerTransfer.IMAGE);
            return;
        }
        if (transfers.isRunning(F91KeplerTransfer.IMAGE) || imageCheckPending) {
            // Its trailing read (or the connect check's) compares against the newest
            // stored frame, and a mismatch spends an upload on it.
            LOG.debug("F91 image upload already running, not starting a second one");
            return;
        }
        imageAttempts = 1;
        sendImage(frame);
    }

    /**
     * Try again, from a failed transfer's notification: send that kind anew from
     * what the app holds now (not a replay of the old writes, which may be stale).
     */
    private void retryTransfer(final String kindName) {
        final F91KeplerTransfer kind;
        try {
            kind = F91KeplerTransfer.valueOf(kindName);
        } catch (final IllegalArgumentException e) {
            LOG.warn("F91 retry for an unknown transfer {}", kindName);
            return;
        }
        if (!getDevice().isInitialized()) {
            return;
        }
        LOG.info("F91 trying the {} transfer again", kind);
        switch (kind) {
            case IMAGE:
                sendStoredImage();
                break;
            case ALARMS:
                onSetAlarms(new ArrayList<>(DBHelper.getAlarms(getDevice())));
                break;
            case SOUNDS:
                if (has32()) {
                    // Every switch as the app shows it, into a fresh read of the watch's
                    // record (read-modify-write as for a single change), and the snooze.
                    soundWriteKeys.addAll(java.util.Arrays.asList(
                            F91KeplerConstants.PREF_SOUND_MUTE, F91KeplerConstants.PREF_SOUND_CALL,
                            F91KeplerConstants.PREF_SOUND_TEXT, F91KeplerConstants.PREF_SOUND_LOWBATT,
                            F91KeplerConstants.PREF_SOUND_LOST));
                    markSnoozeDirty();
                    if (alarmReadGen < 0) {
                        readSoundCfg();
                        readAlarmSounds();
                    }
                }
                break;
            case SETTINGS:
                transfers.queue("set settings again", F91KeplerTransfer.SETTINGS, true, builder -> {
                    addTimeMode(builder);
                    addDst(builder);
                    addRadioSchedule(builder);
                    if (F91KeplerFirmware.atLeast(connectedFw, F91KeplerFirmware.MIN_MODE_ORDER_10)) {
                        addModeOrder(builder);
                    }
                    // Only a brightness change that did not get through: SW3 on the
                    // Flashlight screen sets it on the watch, so it is never pushed blindly.
                    final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
                    if (prefs.getBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, false)
                            && F91KeplerFirmware.atLeast(connectedFw, F91KeplerFirmware.MIN_BRIGHTNESS)) {
                        addBrightness(builder);
                        builder.run(() -> prefs.edit().putBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, false).apply());
                    }
                    if (has31()) {
                        addUiOptions(builder);
                        addCounterNames(builder);
                    }
                });
                break;
            case WEATHER:
                sendWeather(true);
                break;
            case NOTIFICATIONS:
                transfers.queue("notifications again", F91KeplerTransfer.NOTIFICATIONS, true, this::addNotificationState);
                break;
            case TIME:
                transfers.queue("set time again", F91KeplerTransfer.TIME, true, this::addSetTime);
                break;
            default:
                break;
        }
    }

    /**
     * Write the Watch modes list for this connection's firmware to the UI
     * Config ModeOrder char (Main first, then the ticked modes in list order;
     * see {@link F91KeplerModes}). The watch validates, applies and persists
     * it. Sent on change and re-sent on every connect (restorePhoneOwnedConfig)
     * so a reflashed or reset watch gets its order back; the firmware ignores
     * an unchanged order without touching flash. The 3.1 screens are sent only
     * to a 3.1 watch (an older one refuses the whole order over one unknown
     * id), and never more modes than the firmware cycles.
     */
    private void addModeOrder(final TransactionBuilder builder) {
        builder.write(F91KeplerConstants.UUID_CHAR_MODE_ORDER,
                      F91KeplerModes.wireOrder(currentModes(), has31(), maxModes()));
    }

    /** Tell the user when the cycle is over-full and some modes were left out. */
    private void warnIfModesDropped() {
        final int n = F91KeplerModes.droppedCount(currentModes(), has31(), maxModes());
        if (n > 0) {
            GB.toast(getContext(), getContext().getString(R.string.f91_modes_dropped, n, maxModes()),
                     Toast.LENGTH_LONG, GB.WARN);
        }
    }

    private List<String> currentModes() {
        final SharedPreferences prefs =
                GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
        F91KeplerModes.migrate(prefs);   // a watch that connects before the screen was ever opened
        return F91KeplerModes.order(prefs, has31());
    }

    private int maxModes() {
        return F91KeplerModes.maxOptional(connectedFw);
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
