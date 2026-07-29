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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * Keeps the last uploaded 480-byte frame for a watch on disk.
 *
 * <p>The watch holds the image in RAM only, so it is lost on every reset and has
 * to be re-pushed when the phone reconnects — exactly the lifecycle weather
 * already has. Storing it in the device's own directory means Gadgetbridge
 * deletes it along with the device, and both the upload UI and the transport can
 * reach the same bytes.
 */
public final class F91KeplerImageStore {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerImageStore.class);

    private F91KeplerImageStore() {
    }

    public static File file(final GBDevice device) throws IOException {
        final File dir = device.getDeviceCoordinator().getWritableExportDirectory(device, true);
        return new File(dir, F91KeplerConstants.IMAGE_FILE_NAME);
    }

    /**
     * The stored frame, or {@code null} when there is none. A file of the wrong
     * length is treated as absent: a truncated frame would checksum-fail on the
     * watch anyway, and uploading it would just burn a connection window.
     */
    public static byte[] load(final GBDevice device) {
        try {
            final File file = file(device);
            if (!file.isFile() || file.length() != F91KeplerConstants.IMAGE_BYTES) {
                return null;
            }
            final byte[] frame = new byte[F91KeplerConstants.IMAGE_BYTES];
            try (final FileInputStream in = new FileInputStream(file)) {
                int read = 0;
                while (read < frame.length) {
                    final int n = in.read(frame, read, frame.length - read);
                    if (n < 0) {
                        return null;
                    }
                    read += n;
                }
            }
            return frame;
        } catch (final IOException e) {
            LOG.warn("Could not read the stored F91 image", e);
            return null;
        }
    }

    public static void save(final GBDevice device, final byte[] frame) throws IOException {
        if (frame == null || frame.length != F91KeplerConstants.IMAGE_BYTES) {
            throw new IOException("refusing to store a " + (frame == null ? -1 : frame.length)
                    + " byte image frame");
        }
        try (final FileOutputStream out = new FileOutputStream(file(device))) {
            out.write(frame);
        }
    }
}
