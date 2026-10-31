package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class F91KeplerPairAgainIdTest {
    private static final String WATCH_A = "C4:BE:84:12:34:56";
    private static final String WATCH_B = "C4:BE:84:12:34:57";

    @Test
    public void eachWatchHasItsOwnStableId() {
        assertEquals(F91KeplerPairAgainReceiver.notificationId(WATCH_A),
                F91KeplerPairAgainReceiver.notificationId(WATCH_A));
        assertNotEquals(F91KeplerPairAgainReceiver.notificationId(WATCH_A),
                F91KeplerPairAgainReceiver.notificationId(WATCH_B));
    }

    @Test
    public void idsStayInTheForkRange() {
        for (final String address : new String[]{WATCH_A, WATCH_B, "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF"}) {
            final int id = F91KeplerPairAgainReceiver.notificationId(address);
            // above upstream's fixed ids and its random progress ids (0..99999)
            assertTrue(id >= 0x4B50_1000 && id <= 0x4B50_1FFF);
            assertNotEquals(GB.NOTIFICATION_ID_MUSIC_ACCESS, id);
        }
    }

    @Test
    public void forkIdsAreAboveUpstreamIds() {
        assertTrue(GB.NOTIFICATION_ID_MUSIC_ACCESS > 100_000);
        assertTrue(GB.NOTIFICATION_ID_MUSIC_ACCESS > GB.NOTIFICATION_ID_ERROR);
    }
}
