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

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.text.InputFilter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Opens the image screen from the watch's settings (the click is wired here so
 * the activity is launched with the {@link GBDevice} it belongs to), forwards
 * watch-side setting changes to the support class (SEND_KEYS), caps the
 * firmware 3.1 counter-name fields at what the watch can show, and shows each
 * setting only on a watch whose firmware has it ({@link #GATES}).
 *
 * <p>The gated groups are in the screen on every firmware (the coordinator loads
 * them unconditionally) but start hidden; {@link #onDeviceChanged} re-checks
 * the version on every device update, so a watch that connects while the
 * screen is open reveals them the moment its DIS firmware string is read --
 * without reopening the screen. A watch on older firmware, or one whose
 * version is not known yet, never sees them: no hint, no disabled row.
 */
public class F91KeplerSettingsCustomizer implements DeviceSpecificSettingsCustomizer {

    @Override
    public void onPreferenceChange(final Preference preference,
                                   final DeviceSpecificSettingsHandler handler) {
        // Beyond forwarding (SEND_KEYS) the support class does the rest in
        // onSendConfiguration. A new zone changes the name the watch would show.
        if (F91KeplerConstants.PREF_SECOND_ZONE_ID.equals(preference.getKey())) {
            final EditTextPreference name = handler.findPreference(F91KeplerConstants.PREF_SECOND_ZONE_NAME);
            if (name != null && name.getSummaryProvider() != null) {
                name.setSummaryProvider(name.getSummaryProvider());   // re-asks it
            }
        }
    }

    /**
     * Every setting the watch has to hear about when it changes. A key is only
     * forwarded to F91KeplerSupport#onSendConfiguration once a handler is
     * registered for it; without this the watch-side settings (mode order,
     * brightness, sleep window) reached the watch only on the next reconnect.
     * Keys whose preference is absent (a group left out for this firmware) are
     * skipped by addPreferenceHandlerFor. Phone-only settings (alert relay,
     * popup switch) need no entry: the support class reads them when used.
     */
    static final String[] SEND_KEYS = {
            F91KeplerModes.PREF_MODES, F91KeplerModes.PREF_MODES_31,
            F91KeplerConstants.PREF_BRIGHTNESS,
            F91KeplerConstants.PREF_SLEEP_ENABLED, F91KeplerConstants.PREF_SLEEP_START,
            F91KeplerConstants.PREF_SLEEP_END,
            F91KeplerConstants.PREF_WEEKDAY, F91KeplerConstants.PREF_WEEKDAY_LANG,
            F91KeplerConstants.PREF_QUIET_TEXT, F91KeplerConstants.PREF_HOURLY_CHIME,
            F91KeplerConstants.PREF_SECOND_ZONE, F91KeplerConstants.PREF_SECOND_ZONE_ID,
            F91KeplerConstants.PREF_SECOND_ZONE_NAME,
            F91KeplerConstants.PREF_SOUND_MUTE, F91KeplerConstants.PREF_SOUND_CALL,
            F91KeplerConstants.PREF_SOUND_TEXT, F91KeplerConstants.PREF_SOUND_LOWBATT,
            F91KeplerConstants.PREF_SOUND_LOST, F91KeplerConstants.PREF_SOUND_SNOOZE,
            F91KeplerConstants.PREF_SOUND_ALARM_TONE, F91KeplerConstants.PREF_SOUND_CHIME_TONE,
            F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "0",
            F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "1",
            F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "2",
    };

    /**
     * Every setting that needs more than the 3.0.3 baseline, with the first
     * firmware that has it. A setting the connected watch's firmware cannot act on
     * is not shown at all: no hint, no greyed-out row. A watch whose version is not
     * known yet gets the baseline only. Hiding a category hides its children, so a
     * category key covers its whole group; F91KeplerFirmwareGateTest checks that
     * every key in the Kepler settings XMLs is either baseline or covered here.
     */
    static final Map<String, int[]> GATES = new LinkedHashMap<>();

    static {
        // 3.1: UiOptions F2F3, Record F2F4 (counters, forecast), screen ids 10..13
        GATES.put(F91KeplerConstants.PREF_QUIET_TEXT, F91KeplerFirmware.MIN_31);
        GATES.put(F91KeplerConstants.PREF_CATEGORY_FACE_31, F91KeplerFirmware.MIN_31);
        GATES.put(F91KeplerConstants.PREF_CATEGORY_SCREENS_31, F91KeplerFirmware.MIN_31);
        GATES.put(F91KeplerModes.PREF_MODES_31, F91KeplerFirmware.MIN_31);
        // 3.2: the case-back piezo and its Record types
        GATES.put(F91KeplerConstants.PREF_CATEGORY_SOUND_32, F91KeplerFirmware.MIN_32);
        GATES.put(F91KeplerConstants.PREF_SOUND_LOST, F91KeplerFirmware.MIN_LOST_ALERT);
        GATES.put(F91KeplerConstants.PREF_SOUND_ALARM_TONE, F91KeplerFirmware.MIN_TONE_CHOICE);
        GATES.put(F91KeplerConstants.PREF_SOUND_CHIME_TONE, F91KeplerFirmware.MIN_TONE_CHOICE);
        // 3.3.2: UiOptions bit 4 + Record ZONE
        GATES.put(F91KeplerConstants.PREF_SECOND_ZONE, F91KeplerFirmware.MIN_SECOND_ZONE);
        GATES.put(F91KeplerConstants.PREF_SECOND_ZONE_ID, F91KeplerFirmware.MIN_SECOND_ZONE);
        GATES.put(F91KeplerConstants.PREF_SECOND_ZONE_NAME, F91KeplerFirmware.MIN_SECOND_ZONE);
    }

    /** The opposite: shown only while the watch is NOT known to run 3.1 --
     *  the nine-mode list, which the thirteen-mode one replaces. */
    static final String[] KEYS_PRE31 = {
            F91KeplerModes.PREF_MODES,
    };

    /**
     * Whether the setting {@code key} is shown for a watch on {@code firmware}
     * ({@code null}: not known yet, the baseline). Keys outside {@link #GATES}
     * are baseline and always shown, except the pre-3.1 mode list.
     */
    static boolean isVisible(final String key, @Nullable final String firmware) {
        for (final String pre31 : KEYS_PRE31) {
            if (pre31.equals(key)) {
                return !F91KeplerFirmware.has31(firmware);
            }
        }
        final int[] min = GATES.get(key);
        return min == null || F91KeplerFirmware.atLeast(firmware, min);
    }

    /** The hourly chime summary for this firmware: 3.2 also beeps. */
    static int hourlyChimeSummary(@Nullable final String firmware) {
        return F91KeplerFirmware.has32(firmware)
                ? R.string.f91_pref_summary_hourly_chime_beep
                : R.string.f91_pref_summary_hourly_chime;
    }

    @Override
    public void onDeviceChanged(final DeviceSpecificSettingsHandler handler) {
        // Fired on every GBDevice update, including the one right after the
        // firmware version was read on connect (GBDeviceEventVersionInfo).
        applyFirmwareVisibility(handler);
        showSoundSwitches(handler);
        showLive(handler, null);
    }

    // --- Live picture ------------------------------------------------------------

    private static void setUpLive(final DeviceSpecificSettingsHandler handler) {
        final ListPreference preset = handler.findPreference(F91KeplerConstants.PREF_LIVE_PRESET);
        final Preference send = handler.findPreference(F91KeplerConstants.PREF_LIVE_SEND);
        if (preset == null || send == null) {
            return;
        }
        hideTodoWithoutTaskApp(handler.getContext(), preset);
        handler.addPreferenceHandlerFor(F91KeplerConstants.PREF_LIVE_PRESET, (pref, newValue) -> {
            final String value = String.valueOf(newValue);
            askForAccess(handler, F91KeplerLivePicture.Preset.from(value), false);
            showLive(handler, value);
            return true;
        });
        send.setOnPreferenceClickListener(pref -> {
            if (askForAccess(handler, F91KeplerLivePicture.Preset.from(preset.getValue()), true)) {
                return true;            // asked; Send again once allowed
            }
            handler.notifyPreferenceChanged(F91KeplerConstants.PREF_LIVE_SEND);
            return true;
        });
        showLive(handler, null);
    }

    /**
     * Asks for the access {@code preset} reads with, when it is missing. Returns
     * whether it asked. {@code tapped}: the user pressed Send, so also say what
     * is missing (Android shows no dialog once access was refused for good).
     */
    private static boolean askForAccess(final DeviceSpecificSettingsHandler handler,
                                        final F91KeplerLivePicture.Preset preset, final boolean tapped) {
        final Context context = handler.getContext();
        final String[] wanted;
        final int hint;
        switch (preset) {
            case NEXT_EVENT:
                wanted = new String[]{Manifest.permission.READ_CALENDAR};
                hint = R.string.f91_live_allow_calendar;
                break;
            case TODO:
                wanted = todoPermissions(context);
                hint = R.string.f91_live_allow_tasks;
                break;
            default:
                return false;
        }
        final java.util.List<String> missing = new java.util.ArrayList<>();
        for (final String p : wanted) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        // To-do: one task app allowed is enough.
        if (missing.isEmpty() || (preset == F91KeplerLivePicture.Preset.TODO && missing.size() < wanted.length)) {
            return false;
        }
        if (context instanceof Activity) {
            ActivityCompat.requestPermissions((Activity) context, missing.toArray(new String[0]), 0);
        }
        if (tapped || !(context instanceof Activity)) {
            GB.toast(context, context.getString(hint), Toast.LENGTH_LONG, GB.INFO);
        }
        return true;
    }

    /** To do is offered only with a task app installed (or while it is the choice). */
    private static void hideTodoWithoutTaskApp(final Context context, final ListPreference preset) {
        if (todoPermissions(context).length > 0
                || F91KeplerLivePicture.Preset.TODO.value.equals(preset.getValue())) {
            return;
        }
        final CharSequence[] entries = preset.getEntries();
        final CharSequence[] values = preset.getEntryValues();
        final java.util.List<CharSequence> e = new java.util.ArrayList<>();
        final java.util.List<CharSequence> v = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (!F91KeplerLivePicture.Preset.TODO.value.equals(values[i].toString())) {
                e.add(entries[i]);
                v.add(values[i]);
            }
        }
        preset.setEntries(e.toArray(new CharSequence[0]));
        preset.setEntryValues(v.toArray(new CharSequence[0]));
    }

    /** The read permissions of the task apps that are installed. */
    private static String[] todoPermissions(final Context context) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        final PackageManager pm = context.getPackageManager();
        if (pm.resolveContentProvider("org.tasks.api", 0) != null
                || pm.resolveContentProvider("org.tasks", 0) != null) {
            out.add("org.tasks.permission.READ_TASKS");
        }
        if (pm.resolveContentProvider("org.dmfs.tasks", 0) != null) {
            out.add("org.dmfs.permission.READ_TASKS");
        }
        return out.toArray(new String[0]);
    }

    /** Send is shown with a picture chosen; its summary is the last problem or what is drawn. */
    private static void showLive(final DeviceSpecificSettingsHandler handler, @Nullable final String newValue) {
        final ListPreference preset = handler.findPreference(F91KeplerConstants.PREF_LIVE_PRESET);
        final Preference send = handler.findPreference(F91KeplerConstants.PREF_LIVE_SEND);
        final GBDevice device = handler.getDevice();
        if (preset == null || send == null || device == null) {
            return;
        }
        final F91KeplerLivePicture.Preset p = F91KeplerLivePicture.Preset.from(
                newValue != null ? newValue : preset.getValue());
        send.setVisible(p != F91KeplerLivePicture.Preset.OFF);
        final String status = newValue != null ? "" : GBApplication.getDeviceSpecificSharedPrefs(
                device.getAddress()).getString(F91KeplerConstants.PREF_LIVE_STATUS, "");
        if (status != null && !status.isEmpty()) {
            send.setSummary(status);
        } else if (p == F91KeplerLivePicture.Preset.NEXT_EVENT) {
            send.setSummary(R.string.f91_pref_summary_live_calendar);
        } else if (p == F91KeplerLivePicture.Preset.TODO) {
            send.setSummary(R.string.f91_pref_summary_live_todo);
        } else {
            send.setSummary(null);
        }
    }

    /**
     * A setting the watch also holds, changed while it is not connected: flagged so
     * the next connect writes it instead of adopting the watch's value. The flags
     * are the ones the support class sets when it gets the change itself.
     */
    static void markChangedWhileAway(final SharedPreferences prefs, @Nullable final String key) {
        if (key == null) {
            return;
        }
        switch (key) {
            case F91KeplerConstants.PREF_BRIGHTNESS:
                prefs.edit().putBoolean(F91KeplerConstants.PREF_BRIGHTNESS_DIRTY, true).apply();
                break;
            case F91KeplerConstants.PREF_SOUND_SNOOZE:
                prefs.edit().putBoolean(F91KeplerConstants.PREF_SOUND_SNOOZE_DIRTY, true).apply();
                break;
            case F91KeplerConstants.PREF_SOUND_ALARM_TONE:
                prefs.edit().putBoolean(F91KeplerConstants.PREF_SOUND_ALARM_TONE_DIRTY, true).apply();
                break;
            case F91KeplerConstants.PREF_SOUND_CHIME_TONE:
                prefs.edit().putBoolean(F91KeplerConstants.PREF_SOUND_CHIME_TONE_DIRTY, true).apply();
                break;
            case F91KeplerConstants.PREF_SOUND_MUTE:
            case F91KeplerConstants.PREF_SOUND_CALL:
            case F91KeplerConstants.PREF_SOUND_TEXT:
            case F91KeplerConstants.PREF_SOUND_LOWBATT:
            case F91KeplerConstants.PREF_SOUND_LOST: {
                final Set<String> dirty = new java.util.HashSet<>(prefs.getStringSet(
                        F91KeplerConstants.PREF_SOUND_DIRTY_KEYS, Collections.emptySet()));
                if (dirty.add(key)) {
                    prefs.edit().putStringSet(F91KeplerConstants.PREF_SOUND_DIRTY_KEYS, dirty).apply();
                }
                break;
            }
            default:
                break;
        }
    }

    /** The sound switches as stored: the support class adopts the watch's values on connect. */
    private static void showSoundSwitches(final DeviceSpecificSettingsHandler handler) {
        final GBDevice device = handler.getDevice();
        if (device == null) {
            return;
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        final String[] keys = {F91KeplerConstants.PREF_SOUND_MUTE, F91KeplerConstants.PREF_SOUND_CALL,
                F91KeplerConstants.PREF_SOUND_TEXT, F91KeplerConstants.PREF_SOUND_LOWBATT,
                F91KeplerConstants.PREF_SOUND_LOST};
        final boolean[] defaults = {false, true, true, false, false};
        for (int i = 0; i < keys.length; i++) {
            final Preference pref = handler.findPreference(keys[i]);
            if (pref instanceof TwoStatePreference) {
                ((TwoStatePreference) pref).setChecked(prefs.getBoolean(keys[i], defaults[i]));
            }
        }
        final Preference snooze = handler.findPreference(F91KeplerConstants.PREF_SOUND_SNOOZE);
        if (snooze instanceof ListPreference) {
            final ListPreference list = (ListPreference) snooze;
            final String v = prefs.getString(F91KeplerConstants.PREF_SOUND_SNOOZE, "9");
            if (list.findIndexOfValue(v) >= 0) {
                list.setValue(v);           // a value the list does not offer is left as shown
            }
        }
    }

    /** Show each gated setting iff the watch's firmware has it (see {@link #GATES}). */
    private static void applyFirmwareVisibility(final DeviceSpecificSettingsHandler handler) {
        final GBDevice device = handler.getDevice();
        final String fw = device != null ? device.getFirmwareVersion() : null;
        for (final String key : GATES.keySet()) {
            final Preference pref = handler.findPreference(key);
            if (pref != null) {
                pref.setVisible(isVisible(key, fw));
            }
        }
        for (final String key : KEYS_PRE31) {
            final Preference pref = handler.findPreference(key);
            if (pref != null) {
                pref.setVisible(isVisible(key, fw));
            }
        }
        final Preference chime = handler.findPreference(F91KeplerConstants.PREF_HOURLY_CHIME);
        if (chime != null) {
            chime.setSummary(hourlyChimeSummary(fw));
        }
    }

    /** The zone list from the phone's zones, and the name field with the name it will show. */
    private static void setUpSecondZone(final DeviceSpecificSettingsHandler handler) {
        final ListPreference zone = handler.findPreference(F91KeplerConstants.PREF_SECOND_ZONE_ID);
        if (zone != null) {
            final List<String> ids = F91KeplerZone.pickerIds();
            final long now = System.currentTimeMillis();
            final CharSequence[] labels = new CharSequence[ids.size()];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = F91KeplerZone.label(ids.get(i), now);
            }
            zone.setEntries(labels);
            zone.setEntryValues(ids.toArray(new CharSequence[0]));
        }
        final EditTextPreference name = handler.findPreference(F91KeplerConstants.PREF_SECOND_ZONE_NAME);
        if (name != null) {
            name.setOnBindEditTextListener(editText -> editText.setFilters(new InputFilter[]{
                    new InputFilter.AllCaps(), new InputFilter.LengthFilter(3)}));
            name.setSummaryProvider(pref -> {
                final String id = zone != null && zone.getValue() != null ? zone.getValue() : "";
                return id.isEmpty() ? null : handler.getContext().getString(
                        R.string.f91_pref_summary_second_zone_name,
                        F91KeplerZone.name(((EditTextPreference) pref).getText(), id));
            });
        }
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs,
                                  final String rootKey) {
        applyFirmwareVisibility(handler);
        for (final String key : SEND_KEYS) {
            // The listener runs only for a change the user makes (not when the screen
            // loads). With the phone's Bluetooth off Gadgetbridge has no support object
            // for the watch, so onSendConfiguration never runs: remember it here.
            handler.addPreferenceHandlerFor(key, (preference, newValue) -> {
                final GBDevice device = handler.getDevice();
                if (device != null && !device.isConnected()) {
                    markChangedWhileAway(GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()), key);
                }
                return true;
            });
        }
        // Counter names: at most 10 characters on the watch. The field stops
        // there; the support class still sanitizes (ASCII only) before sending.
        for (int i = 0; i < F91KeplerConstants.COUNTERS; i++) {
            final EditTextPreference name =
                    handler.findPreference(F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + i);
            if (name != null) {
                name.setOnBindEditTextListener(editText -> editText.setFilters(new InputFilter[]{
                        new InputFilter.LengthFilter(F91KeplerConstants.COUNTER_NAME_MAX)}));
            }
        }
        setUpSecondZone(handler);
        final Preference soundTest = handler.findPreference(F91KeplerConstants.PREF_SOUND_TEST);
        if (soundTest != null) {
            soundTest.setOnPreferenceClickListener(preference -> {
                final GBDevice d = handler.getDevice();
                if (d != null && d.isInitialized()) {
                    handler.notifyPreferenceChanged(F91KeplerConstants.PREF_SOUND_TEST);
                } else {
                    // Without a link the request would be dropped without a word.
                    GB.toast(handler.getContext(), handler.getContext().getString(R.string.f91_sound_test_connect),
                             Toast.LENGTH_SHORT, GB.INFO);
                }
                return true;
            });
        }
        setUpLive(handler);
        final Preference imageUpload = handler.findPreference(F91KeplerConstants.PREF_IMAGE_UPLOAD);
        if (imageUpload == null) {
            return;
        }
        imageUpload.setOnPreferenceClickListener(preference -> {
            // Deliberately not gated on the connection: an image picked while the
            // watch is away is stored and pushed on the next connect, the same way
            // weather and the notification list are.
            final Intent intent = new Intent(handler.getContext(), F91KeplerImageActivity.class);
            intent.putExtra(GBDevice.EXTRA_DEVICE, handler.getDevice());
            handler.getContext().startActivity(intent);
            return true;
        });
    }

    @Override
    public Set<String> getPreferenceKeysWithSummary() {
        return Collections.emptySet();
    }

    public static final Creator<F91KeplerSettingsCustomizer> CREATOR =
            new Creator<F91KeplerSettingsCustomizer>() {
                @Override
                public F91KeplerSettingsCustomizer createFromParcel(final Parcel in) {
                    return new F91KeplerSettingsCustomizer();
                }

                @Override
                public F91KeplerSettingsCustomizer[] newArray(final int size) {
                    return new F91KeplerSettingsCustomizer[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        // Stateless.
    }
}
