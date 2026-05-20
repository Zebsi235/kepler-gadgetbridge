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

import java.util.UUID;

/**
 * BLE protocol constants for the F91 Kepler smartwatch — a custom firmware
 * for a Casio F-91W internal replacement (upstream:
 * https://github.com/PegorK/F91_Kepler). All GATT characteristics are
 * write-only; the watch never sends notifications/indications.
 *
 * Wire format reference: f91_clock.c / f91_notification.c in the firmware;
 * WatchSim/Sources/WatchSim/F91Protocol.swift mirrors the same semantics.
 */
public final class F91KeplerConstants {

    private F91KeplerConstants() {}

    public static final String DEVICE_NAME = "F91 Kepler";

    // -- Notification service --------------------------------------------
    public static final UUID UUID_SERVICE_NOTIFICATION =
            UUID.fromString("FA35A2F0-7989-11EB-9439-0242AC130002");
    /** uint8 bitmask: bit 0 EMAIL, bit 1 TEXT, bit 2 VOICEMAIL, bit 3 MISSEDCALL. */
    public static final UUID UUID_CHAR_NOTIFICATION_BAR =
            UUID.fromString("FA35A2F1-7989-11EB-9439-0242AC130002");
    /** up to 20 bytes UTF-8 sender name; triggers a 5-second full-screen popup. */
    public static final UUID UUID_CHAR_INCOMING_CALL =
            UUID.fromString("FA35A2F2-7989-11EB-9439-0242AC130002");
    /** up to 20 bytes UTF-8 sender name; triggers a 5-second full-screen popup. */
    public static final UUID UUID_CHAR_INCOMING_TEXT =
            UUID.fromString("FA35A2F3-7989-11EB-9439-0242AC130002");

    // -- Clock service ---------------------------------------------------
    public static final UUID UUID_SERVICE_CLOCK =
            UUID.fromString("FA35B2F0-7989-11EB-9439-0242AC130002");
    /** uint32 little-endian Unix epoch in seconds (UTC). */
    public static final UUID UUID_CHAR_TIME =
            UUID.fromString("FA35B2F1-7989-11EB-9439-0242AC130002");
    /** int16 little-endian seconds west of UTC. Berlin/CET = -3600. */
    public static final UUID UUID_CHAR_TIMEZONE =
            UUID.fromString("FA35B2F2-7989-11EB-9439-0242AC130002");
    /** uint8: 0 = 12-hour mode, 1 = 24-hour mode. */
    public static final UUID UUID_CHAR_TIME_MODE =
            UUID.fromString("FA35B2F3-7989-11EB-9439-0242AC130002");
    /** uint8: 0 = DST off, 1 = DST on. Java ZoneRules already includes DST in
     *  the offset, so we keep this at 0 and let the offset carry it. */
    public static final UUID UUID_CHAR_DST =
            UUID.fromString("FA35B2F4-7989-11EB-9439-0242AC130002");

    // -- Notification bar bits (match f91_notification.h) ---------------
    public static final int BIT_EMAIL       = 0x01;
    public static final int BIT_TEXT        = 0x02;
    public static final int BIT_VOICEMAIL   = 0x04;
    public static final int BIT_MISSEDCALL  = 0x08;

    /** Firmware accepts 0..20 bytes; longer writes return bleInvalidRange. */
    public static final int CONTACT_NAME_MAX_BYTES = 20;

    /** Firmware's full-screen popup window — debounce repeated popups to
     *  avoid clobbering an active one. */
    public static final long FULL_SCREEN_DEBOUNCE_MS = 5_000L;

    /** Per-conversation-key dedupe window. When a notification with the
     *  same Android-native {@code key} arrives within this many ms of the
     *  last popup for that key, the bar bit still updates but the
     *  full-screen popup is suppressed. Prevents a chatty thread from
     *  spamming the watch face. */
    public static final long POPUP_KEY_DEDUPE_MS = 30_000L;

    // -- Preference keys (mirrored in res/xml/devicesettings_f91kepler_*) -
    /** Boolean. When set, writes 1 to the watch's DST char on every clock
     *  sync; otherwise writes 0. Java {@code ZoneId} already folds DST
     *  into the timezone offset, so most users should leave this off. */
    public static final String PREF_DST_ENABLED = "f91kepler_dst_enabled";

    /** Set&lt;String&gt; subset of {@code "email" / "text" / "voicemail" /
     *  "missedcall"}. Controls which notification categories trigger a
     *  full-screen popup write (the bar bitmask still updates regardless). */
    public static final String PREF_POPUP_CATEGORIES = "f91kepler_popup_categories";

    public static final String POPUP_CATEGORY_EMAIL = "email";
    public static final String POPUP_CATEGORY_TEXT = "text";
    public static final String POPUP_CATEGORY_VOICEMAIL = "voicemail";
    public static final String POPUP_CATEGORY_MISSEDCALL = "missedcall";
}
