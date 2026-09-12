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

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler.F91KeplerSupport;

/**
 * Coordinator for the F91 Kepler watch — a custom Casio F-91W internal
 * replacement (CC2640R2F, firmware v3.0.0) that advertises as "F91 Kepler" and
 * exposes nine custom GATT services -- Notification, Image, Clock, Device
 * Control, Music, Find Phone, Alert, Weather and UI Config -- plus the standard
 * Battery and Device Information services. The sensitive characteristics
 * require an encrypted link; the watch uses legacy LE "Just Works" bonding
 * (no PIN).
 */
public class F91KeplerCoordinator extends AbstractBLEDeviceCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("F91 Kepler");
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return F91KeplerSupport.class;
    }

    @Override
    public String getManufacturer() {
        return "Zebsi235";
    }

    @Override
    public int getBondingStyle() {
        // Bond after discovery. F91 firmware v2.0.x requires an encrypted
        // (bonded) link for every clock/notification characteristic
        // (GATT_PERMIT_ENCRYPT_*), so an unbonded connection can't control the
        // watch. Pairing is legacy LE "Just Works" -- no PIN prompt (Secure
        // Connections doesn't fit the CC2640R2 RAM budget; see firmware #79).
        // Same approach the Casio BLE watch coordinators use. Was
        // BONDING_STYLE_NONE while the firmware chars were plaintext.
        return BONDING_STYLE_BOND;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.WATCH;
    }

    @Override
    public boolean supportsFindDevice(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsWeather(@NonNull final GBDevice device) {
        // Firmware Weather Service (E2F0): F91KeplerSupport.onSendWeather pushes
        // the current temperature (user's unit) + condition enum. Screen-os P4.
        return true;
    }

    @Override
    public int getAlarmSlotCount(final GBDevice device) {
        // The firmware Alarm Service holds a single one-shot alarm
        // (CHAR5 AlarmTime + CHAR6 AlarmEnabled). See F91KeplerSupport#onSetAlarms.
        return 1;
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(final GBDevice device) {
        // Offer only what this watch's firmware can act on. Each version-dependent
        // group lives in its own XML so it can be left out; a write to a
        // characteristic the firmware does not have is silently dropped by
        // TransactionBuilder, which showed the user a setting that never took.
        // Unknown version (never connected) -> everything, see F91KeplerFirmware.
        final String fw = device.getFirmwareVersion();
        final List<Integer> xml = new ArrayList<>();
        xml.add(R.xml.devicesettings_timeformat);
        if (!F91KeplerFirmware.knownBelow(fw, F91KeplerFirmware.MIN_ALERTS)) {
            xml.add(R.xml.devicesettings_f91kepler_alerts);
        }
        if (!F91KeplerFirmware.knownBelow(fw, F91KeplerFirmware.MIN_BRIGHTNESS)) {
            xml.add(R.xml.devicesettings_f91kepler_brightness);
        }
        xml.add(R.xml.devicesettings_f91kepler);
        if (!F91KeplerFirmware.knownBelow(fw, F91KeplerFirmware.MIN_RADIO_SCHEDULE)) {
            xml.add(R.xml.devicesettings_f91kepler_sleep);
        }
        if (!F91KeplerFirmware.knownBelow(fw, F91KeplerFirmware.MIN_MODE_ORDER_10)) {
            xml.add(R.xml.devicesettings_f91kepler_modes);
        }
        if (!F91KeplerFirmware.knownBelow(fw, F91KeplerFirmware.MIN_IMAGE)) {
            xml.add(R.xml.devicesettings_f91kepler_image);
        }
        final int[] out = new int[xml.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = xml.get(i);
        }
        return out;
    }

    @Override
    public DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(
            @NonNull final GBDevice device) {
        // Only job: make the "Upload image" entry open F91KeplerImageActivity.
        return new F91KeplerSettingsCustomizer();
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_f91_kepler;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_default;
    }
}
