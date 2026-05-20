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
package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler.F91KeplerSupport;

/**
 * Device coordinator for the F91 Kepler smartwatch. Discovered devices
 * advertise the local name "F91 Kepler" (no service UUID in the
 * advertisement — the 31-byte advertising packet cannot hold a 128-bit
 * UUID alongside the name).
 */
public class F91KeplerCoordinator extends AbstractBLEDeviceCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile(F91KeplerConstants.DEVICE_NAME);
    }

    @Override
    public int getBondingStyle() {
        // Firmware does not advertise LE Secure Connections. Don't bond
        // for now — see Zebsi235/FW91 issue #47 for the long-term plan.
        return BONDING_STYLE_NONE;
    }

    @Override
    public String getManufacturer() {
        return "F91 Kepler";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return F91KeplerSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_f91kepler;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_f91kepler;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.WATCH;
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(GBDevice device) {
        return new int[] {
                R.xml.devicesettings_timeformat,
                R.xml.devicesettings_f91kepler_dst,
                R.xml.devicesettings_f91kepler_popup_categories,
                R.xml.devicesettings_donotdisturb_no_auto,
        };
    }

    @Override
    public int getBatteryCount(final GBDevice device) {
        // F91 Kepler firmware exposes the standard BLE Battery Service
        // (0x180F) since firmware v0.1.x. F91KeplerSupport subscribes via
        // BatteryInfoProfile, so Gadgetbridge displays the level here.
        return 1;
    }

    @Override
    public boolean supportsFindDevice(@NonNull GBDevice device) {
        // The watch has no buzzer, but the firmware's 5-second full-screen
        // popup is enough to spot a watch on a desk by sight. We write a
        // single "find" message to the incoming-text characteristic when
        // the user taps Gadgetbridge's "Find device" button.
        return true;
    }
}
