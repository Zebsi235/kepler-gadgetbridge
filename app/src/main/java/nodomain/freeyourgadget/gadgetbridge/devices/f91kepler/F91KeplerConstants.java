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

import java.util.UUID;

/**
 * GATT contract for the F91 Kepler watch (custom Casio F-91W replacement,
 * CC2640R2F, firmware v1.1.0). Pinned 1:1 to the firmware sources under
 * {@code Firmware/f91_kepler_app/PROFILES/} and mirrored by the WatchSim
 * peer ({@code WatchSim/Sources/WatchSim/F91Protocol.swift}). All multi-byte
 * values are little-endian.
 */
public final class F91KeplerConstants {
    private F91KeplerConstants() {
    }

    /** All custom services share this base; only the 16-bit field varies. */
    private static UUID base(final String shortHex) {
        return UUID.fromString("FA35" + shortHex + "-7989-11EB-9439-0242AC130002");
    }

    // Notification Service: bar bitmask (R/W) + incoming call/text popups (W).
    public static final UUID UUID_SERVICE_NOTIFICATION = base("A2F0");
    public static final UUID UUID_CHAR_NOTIFICATION_BAR = base("A2F1");
    public static final UUID UUID_CHAR_INCOMING_CALL = base("A2F2");
    public static final UUID UUID_CHAR_INCOMING_TEXT = base("A2F3");

    // Clock Service: time (uint32 LE epoch), timezone (int16 LE seconds west),
    // time mode (0=12h/1=24h), DST (0/1). All R/W.
    public static final UUID UUID_SERVICE_CLOCK = base("B2F0");
    public static final UUID UUID_CHAR_TIME = base("B2F1");
    public static final UUID UUID_CHAR_TIMEZONE = base("B2F2");
    public static final UUID UUID_CHAR_TIME_MODE = base("B2F3");
    public static final UUID UUID_CHAR_DST = base("B2F4");

    // Device Control Service: command (W) + diagnostics (R, 10 bytes).
    public static final UUID UUID_SERVICE_DEVICE_CONTROL = base("C2F0");
    public static final UUID UUID_CHAR_DEVICE_COMMAND = base("C2F1");
    public static final UUID UUID_CHAR_DIAGNOSTICS = base("C2F2");

    // Device Control command codes (f91_device_control_service.h).
    public static final byte CMD_RESET = 0x01;
    public static final byte CMD_DISPLAY_ON = 0x10;
    public static final byte CMD_DISPLAY_OFF = 0x11;
    public static final byte CMD_DISPLAY_CLEAR = 0x12;
    public static final byte CMD_DISPLAY_TEST_TEXT = 0x13;

    // Notification bar bitmask bits (f91_notification.h).
    public static final int BIT_EMAIL = 0x01;
    public static final int BIT_TEXT = 0x02;
    public static final int BIT_VOICEMAIL = 0x04;
    public static final int BIT_MISSED_CALL = 0x08;

    /** Incoming call / text names are 0..20 raw UTF-8 bytes (no terminator). */
    public static final int CONTACT_NAME_MAX_BYTES = 20;

    // Device-specific preference keys (see res/xml/devicesettings_f91kepler.xml).
    public static final String PREF_DST = "f91_dst";
    public static final String PREF_NOTIFICATION_POPUP = "f91_notification_popup";
}
