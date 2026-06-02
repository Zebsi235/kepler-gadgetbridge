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

import org.junit.Test;

import java.nio.charset.StandardCharsets;

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
}
