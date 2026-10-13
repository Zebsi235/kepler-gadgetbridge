package nodomain.freeyourgadget.gadgetbridge.service.btle;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BleNamesResolverConnectionTest {
    @Test
    public void aSupervisionTimeoutIsNamedAsSuch() {
        // the same number as a GATT operation status means something else
        assertEquals("GATT_CONN_TIMEOUT", BleNamesResolver.getConnectionStatusString(8));
        assertEquals("GATT_CONN_TERMINATE_PEER_USER", BleNamesResolver.getConnectionStatusString(0x13));
        assertEquals("GATT_CONN_KEY_MISSING", BleNamesResolver.getConnectionStatusString(6));
        assertEquals("conn_status_77", BleNamesResolver.getConnectionStatusString(77));
    }
}
