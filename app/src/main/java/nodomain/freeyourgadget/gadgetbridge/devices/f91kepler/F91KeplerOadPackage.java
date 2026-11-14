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

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A Kepler firmware update as the user picks it: either one TI OAD image
 * ({@code .bin}) or a release zip with both slot links:
 *
 * <pre>
 * kepler-fw-4.0.0.zip
 *   manifest.json   {"format": 1, "version": "4.0.0", "hardware": "kepler-a-r7",
 *                    "images": ["kepler-4.0.0-slot-a.bin", "kepler-4.0.0-slot-b.bin"]}
 *   kepler-4.0.0-slot-a.bin
 *   kepler-4.0.0-slot-b.bin
 * </pre>
 *
 * The zip is the format to publish; the bare image is for bench work. The
 * manifest names the version the app shows and the hardware it is for; the
 * images are tried in order (see {@link F91KeplerOadSession}).
 */
public final class F91KeplerOadPackage {
    /** The only hardware with OAD so far: rev-A boards with the CC2652R7. */
    public static final String HARDWARE_REV_A_R7 = "kepler-a-r7";
    static final int FORMAT = 1;
    /** Larger than two full 336 KB slots: refuse anything bigger before reading it all. */
    static final int MAX_FILE = 1024 * 1024;

    public final String version;
    @Nullable
    public final String hardware;
    public final List<F91KeplerOad.Image> images;

    private F91KeplerOadPackage(final String version, @Nullable final String hardware,
                                final List<F91KeplerOad.Image> images) {
        this.version = version;
        this.hardware = hardware;
        this.images = Collections.unmodifiableList(images);
    }

    public int size() {
        return images.get(0).len;
    }

    private static final class Manifest {
        int format;
        String version;
        String hardware;
        List<String> images;
    }

    /** Read a picked file; {@link F91KeplerOad.BadImage} says why it is not an update. */
    public static F91KeplerOadPackage read(final InputStream in) throws IOException, F91KeplerOad.BadImage {
        final byte[] file = readAll(in);
        if (file.length >= 4 && file[0] == 'P' && file[1] == 'K' && file[2] == 3 && file[3] == 4) {
            return fromZip(file);
        }
        final F91KeplerOad.Image image = F91KeplerOad.parse(file);
        final List<F91KeplerOad.Image> one = new ArrayList<>();
        one.add(image);
        return new F91KeplerOadPackage(image.versionText(), null, one);
    }

    private static F91KeplerOadPackage fromZip(final byte[] file) throws IOException, F91KeplerOad.BadImage {
        final Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    entries.put(e.getName(), readAll(zip));
                }
            }
        }
        final byte[] json = entries.get("manifest.json");
        if (json == null) {
            throw new F91KeplerOad.BadImage("no manifest.json in the zip");
        }
        final Manifest m;
        try {
            m = new Gson().fromJson(new String(json, StandardCharsets.UTF_8), Manifest.class);
        } catch (final JsonParseException ex) {
            throw new F91KeplerOad.BadImage("manifest.json is not valid JSON");
        }
        if (m == null || m.format != FORMAT) {
            throw new F91KeplerOad.BadImage("unknown package format");
        }
        if (m.version == null || m.version.trim().isEmpty() || m.images == null || m.images.isEmpty()) {
            throw new F91KeplerOad.BadImage("manifest without version or images");
        }
        final List<F91KeplerOad.Image> images = new ArrayList<>();
        for (final String name : m.images) {
            final byte[] bin = entries.get(name);
            if (bin == null) {
                throw new F91KeplerOad.BadImage("missing " + name);
            }
            final F91KeplerOad.Image image = F91KeplerOad.parse(bin);
            if (!images.isEmpty() && !images.get(0).softVer.equals(image.softVer)) {
                throw new F91KeplerOad.BadImage("the images are from different builds");
            }
            images.add(image);
        }
        return new F91KeplerOadPackage(m.version.trim(), m.hardware, images);
    }

    /** True when this package may go to the given hardware ({@code null} hardware: bench image, any). */
    public boolean fits(final String watchHardware) {
        return hardware == null || hardware.equals(watchHardware);
    }

    private static byte[] readAll(final InputStream in) throws IOException, F91KeplerOad.BadImage {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_FILE) {
                throw new F91KeplerOad.BadImage("file too large for a watch update");
            }
        }
        return out.toByteArray();
    }
}
