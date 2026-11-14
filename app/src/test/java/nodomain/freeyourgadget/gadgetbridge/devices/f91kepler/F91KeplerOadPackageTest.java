package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class F91KeplerOadPackageTest {

    private static final String MANIFEST = "{\"format\":1,\"version\":\"4.0.0\",\"hardware\":\"kepler-a-r7\","
            + "\"images\":[\"a.bin\",\"b.bin\"]}";

    @Test
    public void bareImage() throws Exception {
        final F91KeplerOadPackage p = read(F91KeplerOadTest.image(9000, 0, "4012", true));
        assertEquals(1, p.images.size());
        assertEquals("4.0.12", p.version);
        assertNull(p.hardware);
        assertTrue(p.fits("anything"));
        assertEquals(9000, p.size());
    }

    @Test
    public void releaseZip() throws Exception {
        final F91KeplerOadPackage p = read(zip(MANIFEST,
                "a.bin", F91KeplerOadTest.image(9000, 0x00000, "4012", true),
                "b.bin", F91KeplerOadTest.image(9000, 0x58000, "4012", true)));
        assertEquals("4.0.0", p.version);
        assertEquals(2, p.images.size());
        assertEquals(0x58000L, p.images.get(1).startAddr);
        assertTrue(p.fits(F91KeplerOadPackage.HARDWARE_REV_A_R7));
        assertFalse(p.fits("kepler-a-r2f"));
    }

    @Test
    public void refusals() throws Exception {
        bad(zip("{\"format\":1,\"version\":\"4.0.0\",\"images\":[\"a.bin\"]}"), "missing a.bin");
        bad(zip("{\"format\":2,\"version\":\"4.0.0\",\"images\":[\"a.bin\"]}"), "unknown package format");
        bad(zip("not json"), "not valid JSON");
        bad(zip(null, "a.bin", F91KeplerOadTest.image(9000, 0, "4012", true)), "no manifest.json");
        bad(zip(MANIFEST,
                "a.bin", F91KeplerOadTest.image(9000, 0, "4012", true),
                "b.bin", F91KeplerOadTest.image(9000, 0x58000, "4013", true)), "different builds");
        bad(new byte[F91KeplerOadPackage.MAX_FILE + 10], "too large");
        bad("hello".getBytes(StandardCharsets.US_ASCII), "too short");
    }

    private static F91KeplerOadPackage read(final byte[] b) throws Exception {
        return F91KeplerOadPackage.read(new ByteArrayInputStream(b));
    }

    private static void bad(final byte[] b, final String why) throws Exception {
        try {
            read(b);
            fail("expected: " + why);
        } catch (final F91KeplerOad.BadImage e) {
            assertTrue(e.getMessage(), e.getMessage().contains(why));
        }
    }

    private static byte[] zip(final String manifest, final Object... files) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            if (manifest != null) {
                z.putNextEntry(new ZipEntry("manifest.json"));
                z.write(manifest.getBytes(StandardCharsets.UTF_8));
            }
            for (int i = 0; i < files.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) files[i]));
                z.write((byte[]) files[i + 1]);
            }
        }
        return out.toByteArray();
    }
}
