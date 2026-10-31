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

import androidx.annotation.StringRes;

import nodomain.freeyourgadget.gadgetbridge.R;

/**
 * The kinds of transfer from the app to the watch. Each one is shown the same
 * way (busy on the device card, a progress notification, then done or failed
 * with Try again), so a new transfer only has to pick its kind.
 */
public enum F91KeplerTransfer {
    IMAGE(R.string.f91_transfer_image, R.string.f91_image_sent, R.string.f91_transfer_image_failed, true),
    ALARMS(R.string.f91_transfer_alarms, R.string.f91_transfer_alarms_done, R.string.f91_transfer_alarms_failed, true),
    SOUNDS(R.string.f91_transfer_sounds, R.string.f91_transfer_sounds_done, R.string.f91_transfer_sounds_failed, false),
    SETTINGS(R.string.f91_transfer_settings, R.string.f91_transfer_settings_done, R.string.f91_transfer_settings_failed, false),
    WEATHER(R.string.f91_transfer_weather, R.string.f91_transfer_weather_done, R.string.f91_transfer_weather_failed, true),
    NOTIFICATIONS(R.string.f91_transfer_notifications, R.string.f91_transfer_notifications_done, R.string.f91_transfer_notifications_failed, true),
    TIME(R.string.f91_transfer_time, R.string.f91_transfer_time_done, R.string.f91_transfer_time_failed, true);

    /** "Sending … to the watch", on the device card and the progress notification. */
    @StringRes
    public final int busyText;
    /** Shown once the watch took a transfer the user started. */
    @StringRes
    public final int doneText;
    /** The failure notification's text. */
    @StringRes
    public final int failedText;
    /** The next connect sends it anyway, so a dropped link is not a failure to report. */
    public final boolean resentOnConnect;

    F91KeplerTransfer(@StringRes final int busyText, @StringRes final int doneText,
                      @StringRes final int failedText, final boolean resentOnConnect) {
        this.busyText = busyText;
        this.doneText = doneText;
        this.failedText = failedText;
        this.resentOnConnect = resentOnConnect;
    }
}
