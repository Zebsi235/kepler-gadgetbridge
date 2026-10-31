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

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The images recently sent to a watch, newest first, kept on the phone so the
 * gallery can offer them again. One 480-byte frame per file, named by the time
 * it was sent; sending an image that is already there moves it to the front
 * instead of keeping a copy. Shared by all watches, like a photo gallery.
 */
public final class F91KeplerImageLibrary {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerImageLibrary.class);

    public static final int MAX_RECENT = 12;
    private static final String SUFFIX = ".bin";

    /** One recent image: {@link #id} names it for {@link #delete}. */
    public static final class Entry {
        public final String id;
        public final byte[] frame;

        Entry(final String id, final byte[] frame) {
            this.id = id;
            this.frame = frame;
        }
    }

    private final File dir;

    public F91KeplerImageLibrary(final File dir) {
        this.dir = dir;
    }

    public static F91KeplerImageLibrary forApp(final Context context) {
        return new F91KeplerImageLibrary(new File(context.getFilesDir(), "f91kepler_images"));
    }

    /** Newest first; files of the wrong size are skipped. */
    public List<Entry> recent() {
        final File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
        final List<Entry> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (final File f : files) {
            final String id = f.getName().substring(0, f.getName().length() - SUFFIX.length());
            // Only our own time-stamped files: a foreign name would sort first forever.
            if (f.length() != F91KeplerConstants.IMAGE_BYTES || !id.matches("\\d+")) {
                continue;
            }
            try {
                out.add(new Entry(id, read(f)));
            } catch (final IOException e) {
                LOG.warn("Could not read the gallery image {}", f, e);
            }
        }
        return out;
    }

    /** Put a sent frame at the front, dropping an identical older copy and the oldest beyond {@link #MAX_RECENT}. */
    public void add(final byte[] frame, final long nowMillis) throws IOException {
        if (frame == null || frame.length != F91KeplerConstants.IMAGE_BYTES) {
            throw new IOException("not an image frame");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        final List<Entry> before = recent();
        for (final Entry e : before) {
            if (Arrays.equals(e.frame, frame)) {
                delete(e.id);
            }
        }
        // Zero-padded, so names sort in time order; never older than the newest file.
        long stamp = nowMillis;
        if (!before.isEmpty()) {
            try {
                stamp = Math.max(stamp, Long.parseLong(before.get(0).id) + 1);
            } catch (final NumberFormatException ignored) {
                // a foreign file name: keep the clock's value
            }
        }
        try (final FileOutputStream out = new FileOutputStream(new File(dir, String.format(Locale.ROOT, "%015d", stamp) + SUFFIX))) {
            out.write(frame);
        }
        final List<Entry> after = recent();
        for (int i = MAX_RECENT; i < after.size(); i++) {
            delete(after.get(i).id);
        }
    }

    /** java.nio.file needs Android 8; the app runs from Android 6. */
    private static byte[] read(final File f) throws IOException {
        final byte[] frame = new byte[F91KeplerConstants.IMAGE_BYTES];
        try (final FileInputStream in = new FileInputStream(f)) {
            int got = 0;
            while (got < frame.length) {
                final int n = in.read(frame, got, frame.length - got);
                if (n < 0) {
                    throw new IOException("short gallery file " + f);
                }
                got += n;
            }
        }
        return frame;
    }

    public void delete(final String id) {
        final File f = new File(dir, id + SUFFIX);
        if (f.exists() && !f.delete()) {
            LOG.warn("Could not delete the gallery image {}", f);
        }
    }
}
