package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/** Record ZONE 0x0E (firmware 3.3.2): offsets east, the next change, the name. */
public class F91KeplerZoneTest {
    /** Saturday 31 Oct 2026, 12:00 UTC. */
    private static final long NOW = 1_793_448_000_000L;

    private static int[] fields(final byte[] p) {
        final ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        return new int[]{b.getInt(), b.getInt(), b.getInt()};
    }

    @Test
    public void newYorkBeforeTheEndOfSummerTime() {
        final byte[] p = F91KeplerZone.payload(TimeZone.getTimeZone("America/New_York"), NOW, "NYC");
        assertEquals(15, p.length);
        // EDT now, EST from Sunday 1 Nov 2026 06:00 UTC on
        assertArrayEquals(new int[]{-14400, 1_793_512_800, -18000}, fields(p));
        assertEquals("NYC", new String(p, 12, 3));
    }

    @Test
    public void aZoneWithoutChangesSendsNoChange() {
        assertArrayEquals(new int[]{32400, 0, 32400},
                fields(F91KeplerZone.payload(TimeZone.getTimeZone("Asia/Tokyo"), NOW, "TYO")));
        assertArrayEquals(new int[]{20700, 0, 20700},
                fields(F91KeplerZone.payload(TimeZone.getTimeZone("Asia/Kathmandu"), NOW, "KAT")));
    }

    @Test
    public void southernAndQuarterHourZones() {
        assertArrayEquals(new int[]{39600, 1_806_768_000, 36000},
                fields(F91KeplerZone.payload(TimeZone.getTimeZone("Australia/Sydney"), NOW, "SYD")));
        // UTC+13:45 now, +12:45 from April: both inside the watch's range
        assertArrayEquals(new int[]{49500, 1_806_760_800, 45900},
                fields(F91KeplerZone.payload(TimeZone.getTimeZone("Pacific/Chatham"), NOW, "CHA")));
    }

    @Test
    public void theChangeIsFoundToTheSecond() {
        assertEquals(1_806_195_600_000L,
                F91KeplerZone.nextChange(TimeZone.getTimeZone("Europe/Vienna"), NOW));
    }

    @Test
    public void offsetsTheWatchCannotTakeSendNothing() {
        assertNull(F91KeplerZone.payload(new SimpleTimeZone(15 * 3600 * 1000, "X"), NOW, "XXX"));
        assertNull(F91KeplerZone.payload(new SimpleTimeZone(3600 * 1000 + 30_000, "X"), NOW, "XXX"));
        assertNull(F91KeplerZone.payload(TimeZone.getTimeZone("Asia/Tokyo"), NOW, "TY"));
    }

    @Test
    public void namesAreThreeCapitals() {
        assertEquals("NYC", F91KeplerZone.name(null, "America/New_York"));
        assertEquals("NYC", F91KeplerZone.name("", "America/New_York"));
        assertEquals("BIG", F91KeplerZone.name("big apple", "America/New_York"));
        assertEquals("NYC", F91KeplerZone.name("ny", "America/New_York"));   // too short: the zone's
        assertEquals("ZUR", F91KeplerZone.name("Zürich", "Europe/Vienna"));
        assertEquals("VIE", F91KeplerZone.autoName("Europe/Vienna"));
        assertEquals("POS", F91KeplerZone.autoName("America/Port_of_Spain"));
        assertEquals("HCM", F91KeplerZone.autoName("Asia/Ho_Chi_Minh"));
        assertEquals("SDO", F91KeplerZone.autoName("America/Santo_Domingo"));
        assertEquals("REY", F91KeplerZone.autoName("Atlantic/Reykjavik"));
        for (final String id : F91KeplerZone.pickerIds()) {
            assertTrue(id, F91KeplerZone.autoName(id).matches("[A-Z]{3}"));
        }
    }

    @Test
    public void thePickerListsRegionCityZones() {
        final List<String> ids = F91KeplerZone.pickerIds();
        assertTrue(ids.contains("Europe/Vienna"));
        assertTrue(ids.contains("America/New_York"));
        assertFalse(ids.contains("Etc/GMT+5"));
        assertFalse(ids.contains("UTC"));
        assertEquals("America/New York (UTC-04:00)", F91KeplerZone.label("America/New_York", NOW));
        assertEquals("Asia/Kathmandu (UTC+05:45)", F91KeplerZone.label("Asia/Kathmandu", NOW));
    }
}
