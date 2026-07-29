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

import androidx.annotation.NonNull;
import androidx.preference.Preference;

import java.util.Collections;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Opens the image screen from the watch's settings. The preference is a plain
 * keyed entry in {@code devicesettings_f91kepler.xml}; the click is wired here so
 * the activity is launched with the {@link GBDevice} it belongs to.
 */
public class F91KeplerSettingsCustomizer implements DeviceSpecificSettingsCustomizer {

    @Override
    public void onPreferenceChange(final Preference preference,
                                   final DeviceSpecificSettingsHandler handler) {
        // No preference needs a side effect beyond what the support class already
        // handles via onSendConfiguration.
    }

    @Override
    public void customizeSettings(final DeviceSpecificSettingsHandler handler, final Prefs prefs,
                                  final String rootKey) {
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
