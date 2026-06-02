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

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler.F91KeplerSupport;

/**
 * Coordinator for the F91 Kepler watch — a custom Casio F-91W internal
 * replacement (CC2640R2F, firmware v1.1.0) that advertises as "F91 Kepler" and
 * exposes its own Notification / Clock / Device-Control GATT services plus the
 * standard Battery Service. The sensitive characteristics require an encrypted
 * link; firmware v2.0.x uses legacy LE "Just Works" bonding (no PIN).
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
    public int getAlarmSlotCount(final GBDevice device) {
        // The firmware Alarm Service holds a single one-shot alarm
        // (CHAR5 AlarmTime + CHAR6 AlarmEnabled). See F91KeplerSupport#onSetAlarms.
        return 1;
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(final GBDevice device) {
        return new int[]{
                R.xml.devicesettings_timeformat,
                R.xml.devicesettings_f91kepler,
        };
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
