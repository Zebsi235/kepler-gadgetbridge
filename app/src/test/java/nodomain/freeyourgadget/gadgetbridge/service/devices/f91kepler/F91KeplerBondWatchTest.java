package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class F91KeplerBondWatchTest {
    private static final int TIMEOUT = 0x08, KEY_MISSING = 0x06, AUTH = 0x05,
            ENCRYPTION = 0x0F, MIC = 0x3D, AUTH_FAIL = 0x89, GATT_ERROR = 0x85;

    private static boolean link(final F91KeplerBondWatch w, final int downStatus, final boolean bonded) {
        w.onLinkUp();
        return w.onLinkDown(downStatus, bonded);
    }

    @Test
    public void twoKeyFailuresInARowOfferPairAgainOnce() {
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        assertFalse(link(w, KEY_MISSING, true));
        assertTrue(link(w, KEY_MISSING, true));
        assertFalse("offered once per episode", link(w, KEY_MISSING, true));
    }

    @Test
    public void aFailedEncryptedWriteCountsEvenWhenTheLinkEndsPlainly() {
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        for (int i = 0; i < 2; i++) {
            w.onLinkUp();
            w.onOperationStatus(ENCRYPTION);
            assertEquals(i == 1, w.onLinkDown(0x13, true));
        }
    }

    @Test
    public void radioDropsNeverLookLikeALostBond() {
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        for (int i = 0; i < 10; i++) {
            assertFalse(link(w, TIMEOUT, true));
            assertFalse(link(w, GATT_ERROR, true));
        }
        assertEquals(0, w.failedLinks());
    }

    @Test
    public void aGoodLinkInBetweenResetsTheCountAndTheOffer() {
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        assertFalse(link(w, AUTH, true));
        w.onLinkUp();
        w.onInitialized();
        assertFalse(w.onLinkDown(TIMEOUT, true));
        assertFalse(link(w, MIC, true));
        assertTrue(link(w, AUTH_FAIL, true));
        w.onLinkUp();
        w.onInitialized();
        w.onLinkDown(TIMEOUT, true);
        assertFalse(link(w, AUTH, true));
        assertTrue("a new episode may offer again", link(w, AUTH, true));
    }

    @Test
    public void aSecurityFailureAfterInitialisationIsNotAStaleBond() {
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        for (int i = 0; i < 3; i++) {
            w.onLinkUp();
            w.onInitialized();
            w.onOperationStatus(AUTH);
            assertFalse(w.onLinkDown(AUTH, true));
        }
    }

    @Test
    public void withoutABondNothingIsStale() {
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        assertFalse(link(w, KEY_MISSING, false));
        assertFalse(link(w, KEY_MISSING, false));
        assertEquals(0, w.failedLinks());
    }

    @Test
    public void anUnsupportedRequestIsNotAKeyFailure() {
        // GATT 0x06 = REQUEST_NOT_SUPPORTED on a read/write, unlike HCI 0x06 on a disconnect
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        for (int i = 0; i < 3; i++) {
            w.onLinkUp();
            w.onOperationStatus(0x06);
            assertFalse(w.onLinkDown(0x13, true));
        }
        assertEquals(0, w.failedLinks());
    }

    @Test
    public void aLinkStuckAfterASecurityFailureWaitsForPairingThenDrops() {
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        w.onLinkUp();
        assertEquals(F91KeplerBondWatch.StuckLink.NONE, w.stuckLink(false));
        assertTrue("before initialising", w.onOperationStatus(AUTH));
        assertEquals(F91KeplerBondWatch.StuckLink.WAIT, w.stuckLink(true));
        assertEquals(F91KeplerBondWatch.StuckLink.DROP, w.stuckLink(false));
        // the dropped link is then counted like any other failed one
        assertFalse(w.onLinkDown(0x16, true));
        assertEquals(1, w.failedLinks());
    }

    @Test
    public void anInitialisedLinkIsNeverDropped() {
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        w.onLinkUp();
        w.onInitialized();
        assertFalse("after initialising", w.onOperationStatus(AUTH));
        assertEquals(F91KeplerBondWatch.StuckLink.NONE, w.stuckLink(false));
        assertFalse("not a security status", w.onOperationStatus(GATT_ERROR));
    }

    @Test
    public void aNewLinkForgetsTheLastLinksSecurityFailure() {
        final F91KeplerBondWatch w = new F91KeplerBondWatch();
        w.onLinkUp();
        w.onOperationStatus(AUTH);
        w.onLinkUp();
        assertEquals(F91KeplerBondWatch.StuckLink.NONE, w.stuckLink(false));
    }
}
