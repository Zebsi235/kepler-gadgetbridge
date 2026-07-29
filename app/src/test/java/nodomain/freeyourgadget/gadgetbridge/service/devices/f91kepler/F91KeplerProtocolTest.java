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
package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventMusicControl;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;

/**
 * Pins the F91 Kepler serializers to the firmware wire contract (little-endian,
 * signed-int16 timezone-west, UTF-8 contact truncation). These mirror the
 * companion app's F91ProtocolTest and WatchSim's length checks.
 */
public class F91KeplerProtocolTest {

    @Test
    public void time_isUint32LittleEndian() {
        assertArrayEquals(
                new byte[]{0x04, 0x03, 0x02, 0x01},
                F91KeplerProtocol.time(0x01020304L));
    }

    @Test
    public void timezoneWest_berlinCetIsNegativeInt16LE() {
        // Berlin/CET is east of UTC → -3600 s west. -3600 == 0xF1F0 → LE {F0, F1}.
        assertArrayEquals(
                new byte[]{(byte) 0xF0, (byte) 0xF1},
                F91KeplerProtocol.timezoneWest(-3600));
    }

    @Test
    public void timezoneWest_pstIsPositiveInt16LE() {
        // PST is west of UTC → +28800 s west. 28800 == 0x7080 → LE {80, 70}.
        assertArrayEquals(
                new byte[]{(byte) 0x80, 0x70},
                F91KeplerProtocol.timezoneWest(28800));
    }

    @Test
    public void timeModeAndDst_areSingleByteFlags() {
        assertArrayEquals(new byte[]{0x01}, F91KeplerProtocol.timeMode(true));
        assertArrayEquals(new byte[]{0x00}, F91KeplerProtocol.timeMode(false));
        assertArrayEquals(new byte[]{0x01}, F91KeplerProtocol.dst(true));
        assertArrayEquals(new byte[]{0x00}, F91KeplerProtocol.dst(false));
    }

    @Test
    public void contactName_truncatesAsciiToMaxBytes() {
        final String name = "abcdefghijklmnopqrstuvwxyz"; // 26 ASCII chars
        final byte[] out = F91KeplerProtocol.contactName(name);
        assertEquals(F91KeplerConstants.CONTACT_NAME_MAX_BYTES, out.length);
        assertArrayEquals("abcdefghijklmnopqrst".getBytes(StandardCharsets.UTF_8), out);
    }

    @Test
    public void contactName_neverSplitsMultiByteCodepoint() {
        // "ä" is 2 UTF-8 bytes; 11 of them = 22 bytes > 20. Must stop at 10 (=20 bytes).
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            sb.append('ä');
        }
        final byte[] out = F91KeplerProtocol.contactName(sb.toString());
        assertEquals(20, out.length);
        // Decodes cleanly back to 10 'ä' with no replacement char.
        assertEquals("ääääääääää", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    public void weatherTemperature_isSignedByteClampedToInt8() {
        assertArrayEquals(new byte[]{23}, F91KeplerProtocol.weatherTemperature(23));
        assertArrayEquals(new byte[]{(byte) -5}, F91KeplerProtocol.weatherTemperature(-5));
        assertArrayEquals(new byte[]{127}, F91KeplerProtocol.weatherTemperature(200));    // clamp high
        assertArrayEquals(new byte[]{(byte) -128}, F91KeplerProtocol.weatherTemperature(-200)); // clamp low
    }

    @Test
    public void weatherCondition_clampsToEnumRange() {
        assertArrayEquals(new byte[]{0}, F91KeplerProtocol.weatherCondition(0));
        assertArrayEquals(new byte[]{7}, F91KeplerProtocol.weatherCondition(7));
        assertArrayEquals(new byte[]{7}, F91KeplerProtocol.weatherCondition(99)); // out of range
    }

    @Test
    public void owmToCondition_mapsGroupsToWatchEnum() {
        assertEquals(F91KeplerConstants.WX_STORM, F91KeplerProtocol.owmToCondition(211));
        assertEquals(F91KeplerConstants.WX_RAIN, F91KeplerProtocol.owmToCondition(300));
        assertEquals(F91KeplerConstants.WX_RAIN, F91KeplerProtocol.owmToCondition(500));
        assertEquals(F91KeplerConstants.WX_HEAVY_RAIN, F91KeplerProtocol.owmToCondition(502));
        assertEquals(F91KeplerConstants.WX_SNOW, F91KeplerProtocol.owmToCondition(601));
        assertEquals(F91KeplerConstants.WX_FOG, F91KeplerProtocol.owmToCondition(741));
        assertEquals(F91KeplerConstants.WX_SUN, F91KeplerProtocol.owmToCondition(800));
        assertEquals(F91KeplerConstants.WX_HALF_SUN, F91KeplerProtocol.owmToCondition(801));
        assertEquals(F91KeplerConstants.WX_CLOUD, F91KeplerProtocol.owmToCondition(804));
    }

    @Test
    public void musicCommand_mapsPlaybackBytesToMediaEvents() {
        assertEquals(GBDeviceEventMusicControl.Event.PLAYPAUSE,
                F91KeplerProtocol.musicCommand(F91KeplerConstants.MUSIC_CMD_PLAY_PAUSE));
        assertEquals(GBDeviceEventMusicControl.Event.NEXT,
                F91KeplerProtocol.musicCommand(F91KeplerConstants.MUSIC_CMD_NEXT));
        assertEquals(GBDeviceEventMusicControl.Event.PREVIOUS,
                F91KeplerProtocol.musicCommand(F91KeplerConstants.MUSIC_CMD_PREV));
    }

    @Test
    public void musicCommand_unknownByteIsUnknownEvent() {
        assertEquals(GBDeviceEventMusicControl.Event.UNKNOWN,
                F91KeplerProtocol.musicCommand((byte) 0x7F));
    }

    @Test
    public void modeOrder_defaultPositionsAreCanonicalFullOrder() {
        // positions notif..image = 1..9 -> Main + canonical order, the firmware's
        // default {0..9} at F91_UI_CONFIG_MAX_MODES = 10.
        assertArrayEquals(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
                F91KeplerProtocol.modeOrder(1, 2, 3, 4, 5, 6, 7, 8, 9));
    }

    @Test
    public void modeOrder_allOffIsMainOnly() {
        assertArrayEquals(new byte[]{0}, F91KeplerProtocol.modeOrder(0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    public void modeOrder_reversedPositionsReorders() {
        // notif=5,timer=4,music=3,stopwatch=2,info=1 (flashlight/findphone off) ->
        // sorted by position: Main, Info(5), Stopwatch(4), Music(3), Timer(2), Notif(1).
        assertArrayEquals(new byte[]{0, 5, 4, 3, 2, 1},
                F91KeplerProtocol.modeOrder(5, 4, 3, 2, 1, 0, 0, 0, 0));
    }

    @Test
    public void modeOrder_offModesAreOmitted() {
        // Only Timer (pos 1) and Stopwatch (pos 2) on -> Main, Timer, Stopwatch.
        assertArrayEquals(new byte[]{0, 2, 4},
                F91KeplerProtocol.modeOrder(0, 1, 0, 2, 0, 0, 0, 0, 0));
    }

    @Test
    public void modeOrder_tiesBreakByCanonicalId() {
        // Timer and Music both at position 1 -> Timer (lower canonical id) first.
        assertArrayEquals(new byte[]{0, 2, 3},
                F91KeplerProtocol.modeOrder(0, 1, 1, 0, 0, 0, 0, 0, 0));
    }

    @Test
    public void modeOrder_flashlightAndFindphoneCanBeOrdered() {
        // Only Flashlight (pos 1) and Find Phone (pos 2) on -> Main, Flashlight, Find Phone.
        assertArrayEquals(new byte[]{0, 6, 7},
                F91KeplerProtocol.modeOrder(0, 0, 0, 0, 0, 1, 2, 0, 0));
    }

    @Test
    public void modeOrder_imageCanBePositionedAndDisabled() {
        // Image (id 9) first after Main, everything else off.
        assertArrayEquals(new byte[]{0, 9},
                F91KeplerProtocol.modeOrder(0, 0, 0, 0, 0, 0, 0, 0, 1));
        // Image off, Bluetooth on -> Image is omitted entirely.
        assertArrayEquals(new byte[]{0, 8},
                F91KeplerProtocol.modeOrder(0, 0, 0, 0, 0, 0, 0, 1, 0));
    }

    @Test
    public void notificationEntry_packsSlotTotalAppSender() {
        // slot 0, total 2, app "Chat", sender "John".
        assertArrayEquals(
                new byte[]{0, 2, 4, 'C', 'h', 'a', 't', 'J', 'o', 'h', 'n'},
                F91KeplerProtocol.notificationEntry(0, 2, "Chat", "John"));
    }

    @Test
    public void notificationEntry_emptyClearsList() {
        assertArrayEquals(new byte[]{0, 0, 0},
                F91KeplerProtocol.notificationEntry(0, 0, "", ""));
    }

    @Test
    public void notificationEntry_truncatesLongFields() {
        final byte[] out = F91KeplerProtocol.notificationEntry(
                1, 3, "VeryLongAppName", "AnExtremelyLongSenderNameHere");
        assertEquals(1, out[0]);
        assertEquals(3, out[1]);
        assertEquals(11, out[2]);                 // app truncated to 11
        assertEquals(3 + 11 + 20, out.length);    // sender truncated to 20
    }

    // --- Image Service ------------------------------------------------------

    @Test
    public void imageControlWrites_areTheBeginAndCommitOpcodes() {
        assertArrayEquals(new byte[]{0x01}, F91KeplerProtocol.imageBegin());
        assertArrayEquals(new byte[]{0x02, (byte) 0xAB}, F91KeplerProtocol.imageCommit((byte) 0xAB));
    }

    @Test
    public void imageChunks_split480BytesInto25FullChunksPlusARemainder() {
        final byte[] frame = new byte[F91KeplerConstants.IMAGE_BYTES];
        final byte[][] chunks = F91KeplerProtocol.imageChunks(frame);

        assertEquals(F91KeplerConstants.IMAGE_CHUNK_COUNT, chunks.length);
        for (int seq = 0; seq < 25; seq++) {
            assertEquals("chunk " + seq + " payload", 19, chunks[seq].length - 1);
            assertEquals("chunk " + seq + " seq byte", seq, chunks[seq][0]);
        }
        // 25 * 19 = 475, so the last chunk carries the remaining 5 bytes.
        assertEquals(5, chunks[25].length - 1);
        assertEquals(25, chunks[25][0]);
    }

    @Test
    public void imageChunks_carryTheFrameInOffsetOrder() {
        // Fill with a position-dependent pattern so a wrong offset cannot pass.
        final byte[] frame = new byte[F91KeplerConstants.IMAGE_BYTES];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) (i * 7 + 1);
        }
        final byte[][] chunks = F91KeplerProtocol.imageChunks(frame);

        // Reassembling the payloads at seq*19 must reproduce the frame exactly.
        final byte[] rebuilt = new byte[F91KeplerConstants.IMAGE_BYTES];
        for (final byte[] chunk : chunks) {
            final int offset = (chunk[0] & 0xFF) * F91KeplerConstants.IMAGE_CHUNK_DATA;
            System.arraycopy(chunk, 1, rebuilt, offset, chunk.length - 1);
        }
        assertArrayEquals(frame, rebuilt);
    }

    @Test(expected = IllegalArgumentException.class)
    public void imageChunks_rejectsAWrongSizedFrame() {
        F91KeplerProtocol.imageChunks(new byte[479]);
    }

    @Test
    public void imageControlMatches_onlyAcceptsAValidMatchingChecksum() {
        assertTrue(F91KeplerProtocol.imageControlMatches(new byte[]{1, 0x5A}, (byte) 0x5A));
        // Watch reports "no image".
        assertFalse(F91KeplerProtocol.imageControlMatches(new byte[]{0, 0x5A}, (byte) 0x5A));
        // Watch holds a different image.
        assertFalse(F91KeplerProtocol.imageControlMatches(new byte[]{1, 0x5B}, (byte) 0x5A));
        // Unusable answers must read as "needs uploading", never as a match.
        assertFalse(F91KeplerProtocol.imageControlMatches(new byte[]{1}, (byte) 0x5A));
        assertFalse(F91KeplerProtocol.imageControlMatches(new byte[0], (byte) 0x5A));
        assertFalse(F91KeplerProtocol.imageControlMatches(null, (byte) 0x5A));
    }
}
