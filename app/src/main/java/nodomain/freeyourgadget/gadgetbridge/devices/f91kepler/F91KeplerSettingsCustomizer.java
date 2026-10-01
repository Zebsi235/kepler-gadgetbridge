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

import android.content.Intent;
import android.os.Parcel;
import android.text.InputFilter;

import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Opens the image screen from the watch's settings (the click is wired here so
 * the activity is launched with the {@link GBDevice} it belongs to), forwards
 * watch-side setting changes to the support class (SEND_KEYS), and caps the
 * firmware 3.1 counter-name fields at what the watch can show.
 */
public class F91KeplerSettingsCustomizer implements DeviceSpecificSettingsCustomizer {

    @Override
    public void onPreferenceChange(final Preference preference,
                                   final DeviceSpecificSettingsHandler handler) {
        // Nothing beyond forwarding (SEND_KEYS) -- the support class does the rest
        // in onSendConfiguration.
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
            F91KeplerConstants.PREF_MODE_POS_NOTIF, F91KeplerConstants.PREF_MODE_POS_TIMER,
            F91KeplerConstants.PREF_MODE_POS_MUSIC, F91KeplerConstants.PREF_MODE_POS_STOPWATCH,
            F91KeplerConstants.PREF_MODE_POS_INFO, F91KeplerConstants.PREF_MODE_POS_FLASHLIGHT,
            F91KeplerConstants.PREF_MODE_POS_FINDPHONE, F91KeplerConstants.PREF_MODE_POS_BLE,
            F91KeplerConstants.PREF_MODE_POS_IMAGE,
            F91KeplerConstants.PREF_MODE_POS_WEATHER, F91KeplerConstants.PREF_MODE_POS_COUNTER0,
            F91KeplerConstants.PREF_MODE_POS_COUNTER1, F91KeplerConstants.PREF_MODE_POS_COUNTER2,
            F91KeplerConstants.PREF_BRIGHTNESS,
            F91KeplerConstants.PREF_SLEEP_ENABLED, F91KeplerConstants.PREF_SLEEP_START,
            F91KeplerConstants.PREF_SLEEP_END,
            F91KeplerConstants.PREF_WEEKDAY, F91KeplerConstants.PREF_WEEKDAY_LANG,
            F91KeplerConstants.PREF_QUIET_TEXT, F91KeplerConstants.PREF_HOURLY_CHIME,
            F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "0",
            F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "1",
            F91KeplerConstants.PREF_COUNTER_NAME_PREFIX + "2",
    };

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs,
                                  final String rootKey) {
        for (final String key : SEND_KEYS) {
            handler.addPreferenceHandlerFor(key);
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
