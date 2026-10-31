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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** The gallery: presets drawn at panel size, and the recent list on disk. */
public class F91KeplerImageLibraryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] frame(final int fill) {
        final byte[] f = new byte[F91KeplerConstants.IMAGE_BYTES];
        java.util.Arrays.fill(f, (byte) fill);
        return f;
    }

    @Test
    public void presetsAreWholeFrames() {
        for (final F91KeplerImagePreset p : F91KeplerImagePreset.values()) {
            assertEquals(F91KeplerConstants.IMAGE_BYTES, p.frame().length);
        }
    }

    @Test
    public void blankIsDarkAndInvertedLogoIsTheLogoFlipped() {
        for (final boolean b : F91KeplerImagePreset.BLANK.pixels()) {
            assertFalse(b);
        }
        final boolean[] logo = F91KeplerImagePreset.LOGO.pixels();
        final boolean[] inverted = F91KeplerImagePreset.LOGO_INVERTED.pixels();
        int lit = 0;
        for (int i = 0; i < logo.length; i++) {
            assertEquals(!logo[i], inverted[i]);
            lit += logo[i] ? 1 : 0;
        }
        assertTrue("logo has lit pixels", lit > 100);
    }

    @Test
    public void recentIsNewestFirstAndEmptyWithoutADirectory() throws IOException {
        final F91KeplerImageLibrary lib = new F91KeplerImageLibrary(new File(tmp.getRoot(), "lib"));
        assertTrue(lib.recent().isEmpty());
        lib.add(frame(1), 1000);
        lib.add(frame(2), 2000);
        final List<F91KeplerImageLibrary.Entry> recent = lib.recent();
        assertEquals(2, recent.size());
        assertArrayEquals(frame(2), recent.get(0).frame);
        assertArrayEquals(frame(1), recent.get(1).frame);
    }

    @Test
    public void sendingAKnownImageMovesItToTheFront() throws IOException {
        final F91KeplerImageLibrary lib = new F91KeplerImageLibrary(tmp.getRoot());
        lib.add(frame(1), 1000);
        lib.add(frame(2), 2000);
        lib.add(frame(1), 3000);
        final List<F91KeplerImageLibrary.Entry> recent = lib.recent();
        assertEquals(2, recent.size());
        assertArrayEquals(frame(1), recent.get(0).frame);
    }

    @Test
    public void aClockGoingBackStillPutsTheNewestFirst() throws IOException {
        final F91KeplerImageLibrary lib = new F91KeplerImageLibrary(tmp.getRoot());
        lib.add(frame(1), 5000);
        lib.add(frame(2), 1000);
        assertArrayEquals(frame(2), lib.recent().get(0).frame);
    }

    @Test
    public void keepsAtMostMaxRecent() throws IOException {
        final F91KeplerImageLibrary lib = new F91KeplerImageLibrary(tmp.getRoot());
        for (int i = 0; i < F91KeplerImageLibrary.MAX_RECENT + 3; i++) {
            lib.add(frame(i), 1000 + i);
        }
        final List<F91KeplerImageLibrary.Entry> recent = lib.recent();
        assertEquals(F91KeplerImageLibrary.MAX_RECENT, recent.size());
        assertArrayEquals(frame(F91KeplerImageLibrary.MAX_RECENT + 2), recent.get(0).frame);
    }

    @Test
    public void deleteRemovesOne() throws IOException {
        final F91KeplerImageLibrary lib = new F91KeplerImageLibrary(tmp.getRoot());
        lib.add(frame(1), 1000);
        lib.add(frame(2), 2000);
        lib.delete(lib.recent().get(0).id);
        assertEquals(1, lib.recent().size());
        assertArrayEquals(frame(1), lib.recent().get(0).frame);
    }

    @Test
    public void ignoresFilesItDidNotWrite() throws IOException {
        final F91KeplerImageLibrary lib = new F91KeplerImageLibrary(tmp.getRoot());
        lib.add(frame(1), 1000);
        java.nio.file.Files.write(new File(tmp.getRoot(), "zzz.bin").toPath(), frame(9));
        final List<F91KeplerImageLibrary.Entry> recent = lib.recent();
        assertEquals(1, recent.size());
        assertArrayEquals(frame(1), recent.get(0).frame);
    }

    @Test(expected = IOException.class)
    public void refusesAWrongSizedFrame() throws IOException {
        new F91KeplerImageLibrary(tmp.getRoot()).add(new byte[10], 1000);
    }
}
