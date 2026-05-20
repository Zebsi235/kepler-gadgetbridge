/*  Copyright (C) 2026 F91 Kepler contributors

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

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_END;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_DO_NOT_DISTURB_NOAUTO_START;
import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.PREF_TIMEFORMAT;

import android.content.Intent;
import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerByteEncoder;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.battery.BatteryInfoProfile;

/**
 * Device support for the F91 Kepler smartwatch. Implements the minimal
 * surface: time sync, notification bar, call popup, text popup. The
 * firmware is write-only on GATT — no read-back / notify subscriptions.
 *
 * <p>User-facing preferences honored:
 * <ul>
 *   <li>{@code timeformat} — 12 h / 24 h / auto, drives the TimeMode char.</li>
 *   <li>{@link F91KeplerConstants#PREF_DST_ENABLED f91kepler_dst_enabled} —
 *       forces DST char to 1 when set. Off by default since Java's
 *       {@code ZoneId} already includes DST in the timezone offset.</li>
 *   <li>{@link F91KeplerConstants#PREF_POPUP_CATEGORIES f91kepler_popup_categories}
 *       — which notification categories trigger a 5-second full-screen
 *       popup. Bar bitmask updates regardless.</li>
 *   <li>{@code do_not_disturb_no_auto} (shared with Mi Band 2 family) —
 *       suppresses full-screen popups within the user's quiet-hours
 *       window. The bar still ticks so the watch face shows pending state.</li>
 * </ul>
 */
public class F91KeplerSupport extends AbstractBTLESingleDeviceSupport {

    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerSupport.class);

    /** Per-active-notification record of which bar bit it occupies, so we
     *  know what to clear on {@link #onDeleteNotification(int)}. */
    private final Map<Integer, Integer> activeNotifBits = new HashMap<>();

    /** Per-notification-key timestamp of the last popup we fired. Used to
     *  suppress repeat full-screen popups when Gadgetbridge re-delivers
     *  updated NotificationSpec instances for the same conversation (same
     *  Android-native {@code key}, different {@code id}). */
    private final Map<String, Long> lastPopupByKey = new HashMap<>();

    /** Last full-screen popup timestamp, for the 5-second debounce that
     *  matches the firmware's full-screen window. */
    private long lastPopupAt = 0L;

    /** Current bar bitmask the watch is showing, so we don't redundantly
     *  re-write the same value. */
    private int currentBarBitmask = 0;

    /** Reused for every battery-level dispatch -- a single GBDeviceEventBatteryInfo
     *  instance is the convention iTagSupport established. */
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private final BatteryInfoProfile<F91KeplerSupport> batteryInfoProfile = new BatteryInfoProfile<>(this);

    private final IntentListener batteryListener = new IntentListener() {
        @Override
        public void notify(Intent intent) {
            if (BatteryInfoProfile.ACTION_BATTERY_INFO.equals(intent.getAction())) {
                BatteryInfo info = intent.getParcelableExtra(BatteryInfoProfile.EXTRA_BATTERY_INFO);
                if (info != null) {
                    batteryCmd.level = (short) info.getPercentCharged();
                    handleGBDeviceEvent(batteryCmd);
                }
            }
        }
    };

    public F91KeplerSupport() {
        super(LOG);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_NOTIFICATION);
        addSupportedService(F91KeplerConstants.UUID_SERVICE_CLOCK);
        // Standard BLE Battery Service (0x180F). The watch firmware updates
        // the level via Battery_SetParameter on a 60 s sample cadence and
        // notifies subscribed clients on change (battery_service.c).
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE);
        batteryInfoProfile.addListener(batteryListener);
        addSupportedProfile(batteryInfoProfile);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);
        // Bump MTU so future ContactName extensions (currently 20 bytes,
        // exactly the default-MTU payload limit) have headroom.
        builder.requestMtu(64);
        // Reset our local bar state — the firmware boots with 0x00 too.
        // Gadgetbridge's NotificationListener will re-deliver still-active
        // notifications shortly after, rebuilding the bar from scratch.
        activeNotifBits.clear();
        lastPopupByKey.clear();
        currentBarBitmask = 0;
        // Push the current time on every (re)connect so the watch catches
        // up after range loss / BT off-on / watch reset.
        writeAllClockChars(builder);
        // Subscribe to BAS 0x180F notifications and pull a first read so
        // the indicator populates within ~1 s of connect rather than
        // waiting for the next firmware-side 60 s sample.
        batteryInfoProfile.enableNotify(builder, true);
        batteryInfoProfile.requestBatteryInfo(builder);
        builder.setDeviceState(GBDevice.State.INITIALIZED);
        return builder;
    }

    // ---------------------------------------------------------------------
    // Time
    // ---------------------------------------------------------------------

    @Override
    public void onSetTime() {
        try {
            TransactionBuilder builder = performInitialized("onSetTime");
            writeAllClockChars(builder);
            builder.queue();
        } catch (IOException e) {
            LOG.warn("onSetTime failed", e);
        }
    }

    private void writeAllClockChars(TransactionBuilder builder) {
        Instant nowUtc = Instant.now();
        int offsetEastSeconds = ZoneId.systemDefault()
                .getRules()
                .getOffset(nowUtc)
                .getTotalSeconds();
        // The watch's TimeZone is "seconds west of UTC" (signed int16),
        // so negate Java's east-of-UTC offset. Java's getOffset() already
        // includes DST in the offset, so the DST char normally stays at 0
        // (the user can force it on via PREF_DST_ENABLED if their watch
        // face displays a separate DST indicator they want lit).
        int secondsWest = -offsetEastSeconds;

        builder.write(F91KeplerConstants.UUID_CHAR_TIME,
                F91KeplerByteEncoder.timeEpoch(nowUtc.getEpochSecond()));
        builder.write(F91KeplerConstants.UUID_CHAR_TIMEZONE,
                F91KeplerByteEncoder.timezoneSecondsWest(secondsWest));
        builder.write(F91KeplerConstants.UUID_CHAR_TIME_MODE,
                F91KeplerByteEncoder.timeMode(prefer24Hour()));
        builder.write(F91KeplerConstants.UUID_CHAR_DST,
                F91KeplerByteEncoder.dst(prefs().getBoolean(F91KeplerConstants.PREF_DST_ENABLED, false)));
    }

    /** Read the user's 12h / 24h / auto preference. "auto" follows the
     *  device locale via {@link android.text.format.DateFormat}. */
    private boolean prefer24Hour() {
        String value = prefs().getString(PREF_TIMEFORMAT, "auto");
        if ("24h".equals(value)) return true;
        if ("am/pm".equals(value)) return false;
        return android.text.format.DateFormat.is24HourFormat(getContext());
    }

    // ---------------------------------------------------------------------
    // Notification bar + popups
    // ---------------------------------------------------------------------

    @Override
    public void onNotification(NotificationSpec spec) {
        LOG.info("F91 onNotification id={} type={} pkg={} sender={}",
                spec.getId(), spec.type, spec.sourceAppId, spec.sender);
        int bit = bitForType(spec.type);
        if (bit == 0) {
            LOG.debug("Ignoring notification with unmapped type: {}", spec.type);
            return;
        }
        activeNotifBits.put(spec.getId(), bit);
        int newBar = recomputeBitmask();

        try {
            TransactionBuilder builder = performInitialized("notification:" + spec.type);
            if (newBar != currentBarBitmask) {
                builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_BAR,
                        F91KeplerByteEncoder.notificationBar(newBar));
                currentBarBitmask = newBar;
            }
            if (shouldFirePopup(bit, spec.key)) {
                String sender = senderFor(spec);
                if (sender != null && !sender.isEmpty()) {
                    builder.write(F91KeplerConstants.UUID_CHAR_INCOMING_TEXT,
                            F91KeplerByteEncoder.contactName(sender));
                    long now = System.currentTimeMillis();
                    lastPopupAt = now;
                    if (spec.key != null && !spec.key.isEmpty()) {
                        lastPopupByKey.put(spec.key, now);
                    }
                }
            }
            builder.queue();
        } catch (IOException e) {
            LOG.warn("onNotification write failed", e);
        }
    }

    @Override
    public void onDeleteNotification(int id) {
        Integer cleared = activeNotifBits.remove(id);
        if (cleared == null) return;
        int newBar = recomputeBitmask();
        if (newBar == currentBarBitmask) return;
        try {
            TransactionBuilder builder = performInitialized("clearNotification:" + id);
            builder.write(F91KeplerConstants.UUID_CHAR_NOTIFICATION_BAR,
                    F91KeplerByteEncoder.notificationBar(newBar));
            currentBarBitmask = newBar;
            builder.queue();
        } catch (IOException e) {
            LOG.warn("onDeleteNotification write failed", e);
        }
    }

    private int recomputeBitmask() {
        int mask = 0;
        for (Integer bit : activeNotifBits.values()) {
            mask |= bit;
        }
        return mask;
    }

    /** Decides whether the bar event also gets a full-screen popup. Gated
     *  by the user's popup-category multi-select, the 5-second debounce,
     *  the quiet-hours window, and a per-notification-key 30-second dedupe
     *  that suppresses repeat popups for updates to the same conversation. */
    private boolean shouldFirePopup(int bit, String key) {
        if (!isCategorySelectedForPopup(bit)) return false;
        if (isInQuietHours()) {
            LOG.debug("Skipping popup during quiet hours (bit=0x{})", Integer.toHexString(bit));
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastPopupAt < F91KeplerConstants.FULL_SCREEN_DEBOUNCE_MS) {
            return false;
        }
        if (key != null && !key.isEmpty()) {
            Long lastForKey = lastPopupByKey.get(key);
            if (lastForKey != null && now - lastForKey < F91KeplerConstants.POPUP_KEY_DEDUPE_MS) {
                LOG.debug("Skipping repeat popup for key {} (last {}ms ago)",
                        key, now - lastForKey);
                return false;
            }
        }
        return true;
    }

    private boolean isCategorySelectedForPopup(int bit) {
        Set<String> selected = prefs().getStringSet(
                F91KeplerConstants.PREF_POPUP_CATEGORIES,
                Collections.singleton(F91KeplerConstants.POPUP_CATEGORY_TEXT));
        switch (bit) {
            case F91KeplerConstants.BIT_EMAIL:
                return selected.contains(F91KeplerConstants.POPUP_CATEGORY_EMAIL);
            case F91KeplerConstants.BIT_TEXT:
                return selected.contains(F91KeplerConstants.POPUP_CATEGORY_TEXT);
            case F91KeplerConstants.BIT_VOICEMAIL:
                return selected.contains(F91KeplerConstants.POPUP_CATEGORY_VOICEMAIL);
            case F91KeplerConstants.BIT_MISSEDCALL:
                return selected.contains(F91KeplerConstants.POPUP_CATEGORY_MISSEDCALL);
            default:
                return false;
        }
    }

    /** Sender-only — never fall back to {@link NotificationSpec#body}: that
     *  would put message content on the watch face. App label is a safer
     *  last resort. (Mirrors Zebsi235/FW91 issue #46.) */
    private String senderFor(NotificationSpec spec) {
        if (spec.sender != null && !spec.sender.isEmpty()) return spec.sender;
        if (spec.title != null && !spec.title.isEmpty()) return spec.title;
        if (spec.sourceName != null && !spec.sourceName.isEmpty()) return spec.sourceName;
        return null;
    }

    private static int bitForType(NotificationType type) {
        if (type == null) return F91KeplerConstants.BIT_TEXT;
        switch (type) {
            case GENERIC_EMAIL:
            case GMAIL:
            case GOOGLE_INBOX:
            case OUTLOOK:
            case YAHOO_MAIL:
            case MAILBOX:
                return F91KeplerConstants.BIT_EMAIL;
            case GENERIC_PHONE:
                // Real incoming calls come via onSetCallState. If a "phone"
                // notification gets here, treat as missed call.
                return F91KeplerConstants.BIT_MISSEDCALL;
            default:
                return F91KeplerConstants.BIT_TEXT;
        }
    }

    // ---------------------------------------------------------------------
    // Calls
    // ---------------------------------------------------------------------

    @Override
    public void onSetCallState(CallSpec spec) {
        if (spec == null || spec.command != CallSpec.CALL_INCOMING) return;
        if (isInQuietHours()) {
            LOG.debug("Skipping incoming-call popup during quiet hours");
            return;
        }
        String displayName = spec.name;
        if (displayName == null || displayName.isEmpty()) {
            displayName = spec.number;
        }
        if (displayName == null || displayName.isEmpty()) return;
        try {
            TransactionBuilder builder = performInitialized("incomingCall");
            builder.write(F91KeplerConstants.UUID_CHAR_INCOMING_CALL,
                    F91KeplerByteEncoder.contactName(displayName));
            lastPopupAt = System.currentTimeMillis();
            builder.queue();
        } catch (IOException e) {
            LOG.warn("onSetCallState write failed", e);
        }
    }

    // ---------------------------------------------------------------------
    // Find device
    // ---------------------------------------------------------------------

    /**
     * "Find device" via the incoming-text full-screen popup. The firmware
     * has no buzzer, but the 5-second on-screen message is sufficient to
     * locate a watch on a desk. Single-shot — Gadgetbridge will call us
     * again with {@code start=false} to clear, but the firmware times out
     * the popup on its own, so we ignore the stop.
     */
    @Override
    public void onFindDevice(boolean start) {
        if (!start) return;
        try {
            TransactionBuilder builder = performInitialized("findDevice");
            String message = getContext().getString(R.string.f91kepler_find_device_message);
            builder.write(F91KeplerConstants.UUID_CHAR_INCOMING_TEXT,
                    F91KeplerByteEncoder.contactName(message));
            lastPopupAt = System.currentTimeMillis();
            builder.queue();
        } catch (IOException e) {
            LOG.warn("onFindDevice write failed", e);
        }
    }

    // ---------------------------------------------------------------------
    // Quiet hours
    // ---------------------------------------------------------------------

    /** Returns {@code true} if the user has scheduled quiet hours enabled
     *  and the wall clock currently falls inside the configured window.
     *  Windows that span midnight (start later than end, e.g. 22:00..06:00)
     *  are handled. */
    private boolean isInQuietHours() {
        SharedPreferences prefs = prefs();
        String mode = prefs.getString(PREF_DO_NOT_DISTURB_NOAUTO, "off");
        if (!"scheduled".equals(mode)) return false;

        LocalTime start = parseTimeOrDefault(prefs.getString(PREF_DO_NOT_DISTURB_NOAUTO_START, "22:00"), 22, 0);
        LocalTime end = parseTimeOrDefault(prefs.getString(PREF_DO_NOT_DISTURB_NOAUTO_END, "06:00"), 6, 0);
        LocalTime now = LocalTime.now();

        if (start.equals(end)) return false;
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // Window wraps midnight.
        return !now.isBefore(start) || now.isBefore(end);
    }

    private static LocalTime parseTimeOrDefault(String raw, int fallbackHour, int fallbackMinute) {
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException e) {
            LOG.warn("Unparseable quiet-hours value: {}", raw);
            return LocalTime.of(fallbackHour, fallbackMinute);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private SharedPreferences prefs() {
        return GBApplication.getDeviceSpecificSharedPrefs(getDevice().getAddress());
    }

    // ---------------------------------------------------------------------
    // Boilerplate (mirrors iTag — write-only device, nothing to handle on read)
    // ---------------------------------------------------------------------

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public boolean getImplicitCallbackModify() {
        return true;
    }

    @Override
    public boolean getSendWriteRequestResponse() {
        return false;
    }
}
