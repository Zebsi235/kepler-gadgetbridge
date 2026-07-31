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
    // NotificationEntry (history feed, write-only): [slot][total][appLen][app][sender].
    public static final UUID UUID_CHAR_NOTIFICATION_ENTRY = base("A2F4");

    // Clock Service: time (uint32 LE epoch), timezone (int16 LE seconds west),
    // time mode (0=12h/1=24h), DST (0/1), alarm time (uint32 LE absolute epoch,
    // 0=disabled), alarm enabled (0/1). All R/W.
    public static final UUID UUID_SERVICE_CLOCK = base("B2F0");
    public static final UUID UUID_CHAR_TIME = base("B2F1");
    public static final UUID UUID_CHAR_TIMEZONE = base("B2F2");
    public static final UUID UUID_CHAR_TIME_MODE = base("B2F3");
    public static final UUID UUID_CHAR_DST = base("B2F4");
    public static final UUID UUID_CHAR_ALARM_TIME = base("B2F5");
    public static final UUID UUID_CHAR_ALARM_ENABLED = base("B2F6");

    // Device Control Service: command (W) + diagnostics (R, 22 bytes as of
    // firmware v2.17.1 -- the block grows by appending, so parse by offset).
    public static final UUID UUID_SERVICE_DEVICE_CONTROL = base("C2F0");
    public static final UUID UUID_CHAR_DEVICE_COMMAND = base("C2F1");
    public static final UUID UUID_CHAR_DIAGNOSTICS = base("C2F2");

    // Weather Service (firmware P4): temperature (int8, already in the user's
    // unit -- the watch shows a bare integer + degree mark) + condition enum.
    // Both write-only.
    public static final UUID UUID_SERVICE_WEATHER = base("E2F0");
    public static final UUID UUID_CHAR_WEATHER_TEMP = base("E2F1");
    public static final UUID UUID_CHAR_WEATHER_CONDITION = base("E2F2");

    // Music Control Service (firmware P6): a single NOTIFY-only PlaybackCmd char.
    // The watch pushes a playback command (the watch is a remote); Gadgetbridge
    // subscribes and drives the phone's media session.
    public static final UUID UUID_SERVICE_MUSIC = base("D2F0");
    public static final UUID UUID_CHAR_MUSIC_CMD = base("D2F1");

    // PlaybackCmd byte values, 1:1 with the firmware's f91_music.h music_cmd_t.
    public static final byte MUSIC_CMD_PLAY_PAUSE = 0x00;
    public static final byte MUSIC_CMD_NEXT = 0x01;
    public static final byte MUSIC_CMD_PREV = 0x02;

    // Find Phone Service: a single NOTIFY-only FindPhoneCmd char. The watch
    // pushes a ring/stop command; Gadgetbridge rings the phone (GBDeviceEventFindPhone).
    public static final UUID UUID_SERVICE_FIND_PHONE = base("D3F0");
    public static final UUID UUID_CHAR_FIND_PHONE_CMD = base("D3F1");

    // FindPhoneCmd byte values, 1:1 with the firmware's f91_find_phone.h.
    public static final byte FIND_PHONE_CMD_RING = 0x00;
    public static final byte FIND_PHONE_CMD_STOP = 0x01;

    // Alert Service (firmware v2.23.0, issue #209): a single notify-only
    // AlertEvent char. The watch pushes it when its TIMER expires or its ALARM
    // fires, so this phone can ring/vibrate as a backup -- the watch's alarm is
    // visual-only (no buzzer, no motor), so an alert nobody is looking at is
    // otherwise missed entirely.
    //
    // Deliberately NOT a reuse of the Find Phone ring: GBDeviceEventFindPhone
    // has a fixed meaning and UX, and reusing it would make an alarm
    // indistinguishable from a find-my-watch ring, with no way to offer the
    // per-event toggles below.
    public static final UUID UUID_SERVICE_ALERT = base("D4F0");
    public static final UUID UUID_CHAR_ALERT_EVENT = base("D4F1");

    /** AlertEvent byte values, 1:1 with the firmware's f91_alert.h. */
    public static final byte ALERT_EVENT_TIMER = 0x00;
    public static final byte ALERT_EVENT_ALARM = 0x01;

    // UI Config Service (firmware P7): a single ModeOrder char carrying the
    // enabled set + display order of the watch's modes as a 1..10 byte array of
    // mode ids (F91_UI_CONFIG_MAX_MODES, raised to 10 in firmware v2.16.0).
    // READ + encrypted-WRITE.
    public static final UUID UUID_SERVICE_UI_CONFIG = base("F2F0");
    public static final UUID UUID_CHAR_MODE_ORDER = base("F2F1");
    /**
     * Brightness, encrypted read + write, one byte (firmware v2.25.0, issue
     * #211). The value is a STEP INDEX on the watch's ladder, not an SSD1306
     * contrast byte: the firmware owns the mapping (f91_brightness.c) precisely
     * so no phone can dim the panel to unreadable. The watch rejects a step it
     * does not have, and persists an accepted one.
     */
    public static final UUID UUID_CHAR_BRIGHTNESS = base("F2F2");

    /**
     * Brightness steps the firmware ladder has, and the step it defaults to
     * (which maps to the contrast the watch used before the setting existed).
     * Mirrors F91_BRIGHTNESS_STEPS / F91_BRIGHTNESS_DEFAULT.
     */
    public static final int BRIGHTNESS_STEPS = 5;
    public static final int BRIGHTNESS_DEFAULT = 2;

    // Image Service (firmware v2.16.0): one full-screen 1-bit image, staged in
    // 19-byte chunks and latched by a checksummed commit. RAM-only on the watch,
    // so Gadgetbridge re-pushes it on reconnect (see F91KeplerImageStore).
    public static final UUID UUID_SERVICE_IMAGE = base("A3F0");
    // ImageChunk, encrypted write: [seq][1..19 data], chunk `seq` lands at seq*19.
    public static final UUID UUID_CHAR_IMAGE_CHUNK = base("A3F1");
    // ImageControl, encrypted read + write: read [valid][xor8], write begin/commit.
    public static final UUID UUID_CHAR_IMAGE_CONTROL = base("A3F2");

    /** ImageControl write opcode: arm a transfer (invalidates the current image). */
    public static final byte IMAGE_CTRL_BEGIN = 0x01;
    /** ImageControl write opcode: latch the staged frame, followed by its xor8. */
    public static final byte IMAGE_CTRL_COMMIT = 0x02;

    /** Panel width in pixels. */
    public static final int IMAGE_WIDTH = 96;
    /** Visible panel height. The 480-byte frame covers 40 rows; row 39 is offscreen. */
    public static final int IMAGE_HEIGHT = 39;
    /** 96 columns × 5 pages — exactly one SSD1306 framebuffer. */
    public static final int IMAGE_BYTES = 480;
    /** Payload bytes per ImageChunk write (the rest of the 20-byte ATT budget). */
    public static final int IMAGE_CHUNK_DATA = 19;
    /** ceil(480 / 19): chunks 0..24 carry 19 bytes, chunk 25 carries the last 5. */
    public static final int IMAGE_CHUNK_COUNT = 26;
    /** Name of the per-device file holding the last uploaded frame. */
    public static final String IMAGE_FILE_NAME = "f91_image.bin";

    // Mode (screen) ids, 1:1 with the firmware's screen_id_t. MODE_MAIN is the
    // pinned home (always present, index 0); the rest are optional.
    public static final byte MODE_MAIN = 0;
    public static final byte MODE_NOTIF = 1;
    public static final byte MODE_TIMER = 2;
    public static final byte MODE_MUSIC = 3;
    public static final byte MODE_STOPWATCH = 4;
    public static final byte MODE_INFO = 5;
    public static final byte MODE_FLASHLIGHT = 6;
    public static final byte MODE_FINDPHONE = 7;
    public static final byte MODE_BLE = 8;        // Bluetooth mode (fw v2.15.0+)
    public static final byte MODE_IMAGE = 9;      // Image mode (fw v2.16.0+)

    // Per-mode position preference keys (Watch-modes ordering). Value is "0"=off
    // or "1".."9" = display position; the watch order is Main, then the optional
    // modes sorted by position (ties broken by canonical id). Main has no pref
    // (always first).
    public static final String PREF_MODE_POS_NOTIF = "f91_mode_pos_notif";
    public static final String PREF_MODE_POS_TIMER = "f91_mode_pos_timer";
    public static final String PREF_MODE_POS_MUSIC = "f91_mode_pos_music";
    public static final String PREF_MODE_POS_STOPWATCH = "f91_mode_pos_stopwatch";
    public static final String PREF_MODE_POS_INFO = "f91_mode_pos_info";
    public static final String PREF_MODE_POS_FLASHLIGHT = "f91_mode_pos_flashlight";
    public static final String PREF_MODE_POS_FINDPHONE = "f91_mode_pos_findphone";
    public static final String PREF_MODE_POS_BLE = "f91_mode_pos_ble";
    public static final String PREF_MODE_POS_IMAGE = "f91_mode_pos_image";

    /** Display brightness step, "0".."4" (issue #211). */
    public static final String PREF_BRIGHTNESS = "f91_brightness";

    // Weather condition enum, 1:1 with the firmware's f91_weather.h / icon table.
    public static final int WX_SUN = 0;
    public static final int WX_HALF_SUN = 1;   // partly cloudy
    public static final int WX_CLOUD = 2;
    public static final int WX_RAIN = 3;
    public static final int WX_HEAVY_RAIN = 4;
    public static final int WX_SNOW = 5;
    public static final int WX_STORM = 6;
    public static final int WX_FOG = 7;

    // Device Control command codes (f91_device_control_service.h).
    public static final byte CMD_RESET = 0x01;
    public static final byte CMD_DISPLAY_ON = 0x10;
    public static final byte CMD_DISPLAY_OFF = 0x11;
    public static final byte CMD_DISPLAY_CLEAR = 0x12;
    public static final byte CMD_DISPLAY_TEST_TEXT = 0x13;
    public static final byte CMD_FIND_ON = 0x14;   // flash the "FIND" alert on the watch (find my watch)
    public static final byte CMD_FIND_OFF = 0x15;  // stop the flashing alert

    // Notification bar bitmask bits (f91_notification.h).
    public static final int BIT_EMAIL = 0x01;
    public static final int BIT_TEXT = 0x02;
    public static final int BIT_VOICEMAIL = 0x04;
    public static final int BIT_MISSED_CALL = 0x08;

    /** Incoming call / text names are 0..20 raw UTF-8 bytes (no terminator). */
    public static final int CONTACT_NAME_MAX_BYTES = 20;

    // Device-specific preference keys (see res/xml/devicesettings_f91kepler.xml).
    /** Ring/vibrate this phone when the watch's TIMER expires (issue #209). */
    public static final String PREF_ALERT_TIMER = "f91_alert_timer";
    /** Ring/vibrate this phone when the watch's ALARM fires (issue #209). */
    public static final String PREF_ALERT_ALARM = "f91_alert_alarm";
    /** How to alert: "ring" or "vibrate". */
    public static final String PREF_ALERT_MODE = "f91_alert_mode";
    public static final String PREF_DST = "f91_dst";
    public static final String PREF_NOTIFICATION_POPUP = "f91_notification_popup";

    /**
     * Default for {@link #PREF_NOTIFICATION_POPUP}: the watch DOES light up for a
     * notification unless the wearer turns it off.
     *
     * A named constant rather than a literal at the call site because the value has
     * to agree with android:defaultValue in res/xml/devicesettings_f91kepler.xml,
     * and the two silently disagreeing is precisely how the watch ended up never
     * waking for a text (issue #2). F91KeplerNotificationPopupDefaultTest asserts
     * the XML and this constant match, so a change to one side fails the build.
     */
    public static final boolean PREF_NOTIFICATION_POPUP_DEFAULT = true;
    /** Opens {@link F91KeplerImageActivity}; also the onSendConfiguration key the
     *  activity uses to ask the service to upload the stored frame. */
    public static final String PREF_IMAGE_UPLOAD = "f91_image_upload";
}
