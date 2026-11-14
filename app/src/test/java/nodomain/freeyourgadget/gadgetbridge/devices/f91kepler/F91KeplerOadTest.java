package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.zip.CRC32;

import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerOadSession.Outcome;

public class F91KeplerOadTest {

    // ---- image file -------------------------------------------------------

    @Test
    public void parsesHeaderAndCutsIdentify() throws Exception {
        final byte[] file = image(5000, 0x58000L, "0400", true);
        final F91KeplerOad.Image img = F91KeplerOad.parse(file);
        assertEquals("CC26x2R1", img.imgId);
        assertEquals(5000, img.len);
        assertEquals(F91KeplerOad.IMG_TYPE_APP, img.imgType);
        assertEquals(0x58000L, img.startAddr);
        assertEquals("0400", img.versionText());
        assertTrue(img.signed);
        final byte[] id = img.identify();
        assertEquals(F91KeplerOad.IDENTIFY_LEN, id.length);
        assertArrayEquals(Arrays.copyOfRange(file, 0, 8), Arrays.copyOfRange(id, 0, 8));
        assertEquals(3, id[8]);                                  // bimVer
        assertEquals(F91KeplerOad.IMG_TYPE_APP, id[12]);         // imgType
        assertEquals(5000, le32(id, 14));                        // len
        assertEquals("0400", new String(id, 18, 4, StandardCharsets.US_ASCII));
    }

    @Test
    public void trailingBytesAfterLenAreNotSent() throws Exception {
        final byte[] file = image(3000, 0, "0001", false);
        final byte[] padded = Arrays.copyOf(file, 4096);
        assertEquals(3000, F91KeplerOad.parse(padded).bytes.length);
    }

    @Test
    public void refusesDamagedFiles() {
        final byte[] good = image(3000, 0, "0001", false);
        bad(new byte[10], "too short");
        final byte[] flipped = good.clone();
        flipped[2000] ^= 1;
        bad(flipped, "CRC");
        final byte[] cut = Arrays.copyOf(good, 2000);
        bad(cut, "does not fit");
        final byte[] noId = good.clone();
        noId[0] = 0;
        bad(noId, "no image id");
    }

    @Test
    public void blocksSplitTheImage() throws Exception {
        final F91KeplerOad.Image img = F91KeplerOad.parse(image(1000, 0, "0001", false));
        assertEquals(5, img.blockCount(244));                    // 240 per block
        final byte[] last = img.block(4, 244);
        assertEquals(4 + 40, last.length);
        assertEquals(4, le32(last, 0));
        assertArrayEquals(Arrays.copyOfRange(img.bytes, 960, 1000), Arrays.copyOfRange(last, 4, 44));
        assertEquals(63, img.blockCount(20));                    // 16 per block
        try {
            img.block(5, 244);
            fail();
        } catch (final IllegalArgumentException expected) {
            // outside
        }
    }

    // ---- session against a simulated watch --------------------------------

    @Test
    public void fullUpdateWithLargeBlocks() throws Exception {
        final Rig rig = new Rig(244);
        rig.run(image(200_000, 0, "0400", true));
        assertEquals(Outcome.DONE, rig.result.outcome);
        assertTrue(rig.watch.enabled);
        assertArrayEquals(rig.images.get(0).bytes, rig.watch.flash());
        assertEquals(834, rig.session.image().blockCount(244));
        assertEquals(0, rig.session.resends());
    }

    @Test
    public void fullUpdateWithDefaultBlocks() throws Exception {
        final Rig rig = new Rig(F91KeplerOad.DEFAULT_BLOCK_SIZE);
        rig.run(image(4_001, 0, "0400", false));
        assertEquals(Outcome.DONE, rig.result.outcome);
        assertArrayEquals(rig.images.get(0).bytes, rig.watch.flash());
    }

    @Test
    public void triesTheOtherSlotImage() throws Exception {
        final Rig rig = new Rig(244);
        rig.watch.acceptStart = 0x58000L;                        // slot A runs, B is free
        rig.run(image(20_000, 0x00000L, "0400", true), image(20_000, 0x58000L, "0400", true));
        assertEquals(Outcome.DONE, rig.result.outcome);
        assertEquals(0x58000L, rig.session.image().startAddr);
        assertArrayEquals(rig.images.get(1).bytes, rig.watch.flash());
    }

    @Test
    public void refusedWhenNoImageFits() throws Exception {
        final Rig rig = new Rig(244);
        rig.watch.acceptStart = 0x12345L;
        rig.run(image(20_000, 0, "0400", true));
        assertEquals(Outcome.FAILED, rig.result.outcome);
        assertEquals(F91KeplerOad.INCOMPATIBLE_IMAGE, rig.result.status);
        assertFalse(rig.result.retryable);
        assertFalse(rig.watch.enabled);
    }

    @Test
    public void wrongImageIdIsRefused() throws Exception {
        final Rig rig = new Rig(244);
        final byte[] file = image(8_000, 0, "0400", true);
        System.arraycopy("OTHERCHP".getBytes(StandardCharsets.US_ASCII), 0, file, 0, 8);
        fixCrc(file);
        rig.run(file);
        assertEquals(F91KeplerOad.INVALID_FILE, rig.result.status);
        assertEquals(0, rig.watch.blockWrites);
    }

    @Test
    public void watchCrcFailureEndsWithoutEnable() throws Exception {
        final Rig rig = new Rig(244);
        rig.watch.corruptBlock = 7;                              // flash write goes bad on the watch
        rig.run(image(20_000, 0, "0400", true));
        assertEquals(Outcome.FAILED, rig.result.outcome);
        assertEquals(F91KeplerOad.CRC_ERR, rig.result.status);
        assertFalse(rig.watch.enabled);
    }

    @Test
    public void dropMidTransferIsRetryableAndRestartsAtZero() throws Exception {
        final Rig rig = new Rig(244);
        rig.dropAfterBlocks = 40;
        rig.run(image(50_000, 0, "0400", true));
        assertEquals(Outcome.FAILED, rig.result.outcome);
        assertTrue(rig.result.retryable);
        assertFalse(rig.watch.enabled);

        // Stock TI target: a new attempt starts at block 0 and still completes.
        final Rig again = new Rig(244);
        again.run(image(50_000, 0, "0400", true));
        assertEquals(Outcome.DONE, again.result.outcome);
        assertEquals(0, again.firstRequested);
    }

    @Test
    public void resumingWatchIsFollowed() throws Exception {
        final Rig rig = new Rig(244);
        rig.watch.resumeFrom = 100;                              // a watch that kept 100 blocks
        final byte[] file = image(50_000, 0, "0400", true);
        rig.watch.preload(Arrays.copyOf(file, 100 * 240));
        rig.run(file);
        assertEquals(Outcome.DONE, rig.result.outcome);
        assertEquals(100, rig.firstRequested);
        assertEquals(209 - 100, rig.watch.blockWrites);
        assertArrayEquals(rig.images.get(0).bytes, rig.watch.flash());
    }

    @Test
    public void lostBlockNotificationStallsThenTimesOut() throws Exception {
        final Rig rig = new Rig(244);
        rig.swallowRequestAfter = 12;
        rig.run(image(20_000, 0, "0400", true));
        assertNull(rig.result);                                  // waiting
        assertEquals(F91KeplerOadSession.STALL_MS, rig.timeoutMs);
        rig.session.onTimeout();
        assertEquals(Outcome.FAILED, rig.result.outcome);
        assertTrue(rig.result.retryable);
    }

    @Test
    public void cancelTellsTheWatch() throws Exception {
        final Rig rig = new Rig(244);
        rig.cancelAfterBlocks = 5;
        rig.run(image(20_000, 0, "0400", true));
        assertEquals(Outcome.CANCELLED, rig.result.outcome);
        assertEquals(F91KeplerOad.CMD_CANCEL_OAD, rig.lastControl);
        assertFalse(rig.watch.enabled);
    }

    @Test
    public void rebootBeforeEnableAnswerCountsAsDone() throws Exception {
        final Rig rig = new Rig(244);
        rig.watch.silentEnable = true;
        rig.run(image(10_000, 0, "0400", true));
        assertNull(rig.result);
        rig.session.onLinkLost();
        assertEquals(Outcome.DONE, rig.result.outcome);
        assertTrue(rig.watch.enabled);
    }

    @Test
    public void finishedIsReportedOnce() throws Exception {
        final Rig rig = new Rig(244);
        rig.run(image(5_000, 0, "0400", true));
        rig.session.onTimeout();
        rig.session.onLinkLost();
        rig.session.cancel();
        assertEquals(1, rig.finishedCalls);
    }

    @Test
    public void batteryGate() {
        assertNotNull(F91KeplerOadSession.refusal(-1, false));
        assertNotNull(F91KeplerOadSession.refusal(29, false));
        assertNull(F91KeplerOadSession.refusal(29, true));
        assertNull(F91KeplerOadSession.refusal(30, false));
    }

    // ---- helpers ----------------------------------------------------------

    /** Links a session to a simulated watch; notifications are delivered in order. */
    private static final class Rig implements F91KeplerOadSession.Port {
        final SimWatch watch;
        F91KeplerOadSession session;
        List<F91KeplerOad.Image> images;
        F91KeplerOadSession.Result result;
        int finishedCalls;
        long timeoutMs;
        int lastControl = -1;
        int firstRequested = -1;
        int dropAfterBlocks = -1;
        int swallowRequestAfter = -1;
        int cancelAfterBlocks = -1;
        private boolean linkUp = true;
        private final ArrayList<Runnable> queue = new ArrayList<>();

        Rig(final int watchBlockSize) {
            watch = new SimWatch(watchBlockSize);
        }

        void run(final byte[]... files) throws Exception {
            images = new ArrayList<>();
            for (final byte[] f : files) {
                images.add(F91KeplerOad.parse(f));
            }
            session = new F91KeplerOadSession(this, images);
            session.start();
            while (!queue.isEmpty() && result == null) {
                queue.remove(0).run();
            }
        }

        @Override
        public void write(final UUID c, final byte[] v) {
            if (!linkUp) {
                return;
            }
            if (c.equals(F91KeplerOad.EXT_CTRL)) {
                lastControl = v[0] & 0xFF;
            }
            if (c.equals(F91KeplerOad.IMG_BLOCK) && watch.blockWrites == dropAfterBlocks) {
                linkUp = false;
                watch.reset();
                queue.add(session::onLinkLost);
                return;
            }
            if (c.equals(F91KeplerOad.IMG_BLOCK) && watch.blockWrites == cancelAfterBlocks) {
                queue.add(session::cancel);
            }
            for (final byte[][] n : watch.write(c, v)) {
                final UUID from = UUID.fromString(new String(n[0], StandardCharsets.US_ASCII));
                final byte[] value = n[1];
                if (from.equals(F91KeplerOad.EXT_CTRL) && value[0] == F91KeplerOad.CMD_BLK_RSP_NOTIF) {
                    if (firstRequested < 0) {
                        firstRequested = le32(value, 2);
                    }
                    if (watch.blockWrites == swallowRequestAfter) {
                        continue;
                    }
                }
                queue.add(() -> {
                    if (from.equals(F91KeplerOad.IMG_IDENTIFY)) {
                        session.onIdentify(value);
                    } else {
                        session.onControl(value);
                    }
                });
            }
        }

        @Override
        public void armTimeout(final long ms) {
            timeoutMs = ms;
        }

        @Override
        public void progress(final int done, final int total) {
            assertTrue(done <= total);
        }

        @Override
        public void finished(final F91KeplerOadSession.Result r) {
            finishedCalls++;
            result = r;
        }
    }

    /**
     * The watch side of SDK 7.10 oad.c, reduced to what the phone sees: identify
     * checks, one block size, strict block order (a wrong block resets to idle),
     * CRC over the written image at the end, enable.
     */
    private static final class SimWatch {
        final int blockSize;
        long acceptStart = -1;
        int corruptBlock = -1;
        int resumeFrom = 0;
        boolean silentEnable;
        boolean enabled;
        int blockWrites;
        private String state = "IDLE";
        private int len;
        private int next;
        private byte[] flash = new byte[0];

        SimWatch(final int blockSize) {
            this.blockSize = blockSize;
        }

        void preload(final byte[] head) {
            flash = head.clone();
        }

        void reset() {
            state = "IDLE";
            next = 0;
        }

        byte[] flash() {
            return Arrays.copyOf(flash, len);
        }

        List<byte[][]> write(final UUID c, final byte[] v) {
            if (c.equals(F91KeplerOad.IMG_IDENTIFY)) {
                int status = F91KeplerOad.SUCCESS;
                if (!"CC26x2R1".equals(new String(v, 0, 8, StandardCharsets.US_ASCII))) {
                    status = F91KeplerOad.INVALID_FILE;
                }
                if (state.equals("IDLE") && status == F91KeplerOad.SUCCESS) {
                    len = le32(v, 14);
                    state = "CONFIG";
                }
                return one(F91KeplerOad.IMG_IDENTIFY, new byte[]{(byte) status});
            }
            if (c.equals(F91KeplerOad.EXT_CTRL)) {
                switch (v[0]) {
                    case F91KeplerOad.CMD_GET_BLK_SZ:
                        return one(c, new byte[]{1, (byte) blockSize, (byte) (blockSize >> 8)});
                    case F91KeplerOad.CMD_START_OAD:
                        if (!state.equals("CONFIG")) {
                            return one(c, new byte[]{3, F91KeplerOad.NOT_STARTED});
                        }
                        state = "DOWNLOAD";
                        next = resumeFrom;
                        if (flash.length < len) {
                            flash = Arrays.copyOf(flash, len);
                        }
                        return one(c, request(F91KeplerOad.SUCCESS, next));
                    case F91KeplerOad.CMD_ENABLE_IMG:
                        if (!state.equals("COMPLETE")) {
                            return one(c, new byte[]{4, F91KeplerOad.DL_NOT_COMPLETE});
                        }
                        enabled = true;
                        return silentEnable ? Collections.emptyList() : one(c, new byte[]{4, 0});
                    case F91KeplerOad.CMD_CANCEL_OAD:
                        reset();
                        return one(c, new byte[]{5, 0});
                    default:
                        return one(c, new byte[]{(byte) 0xFF});
                }
            }
            // Image Block
            if (!state.equals("DOWNLOAD")) {
                reset();
                return one(F91KeplerOad.EXT_CTRL, request(F91KeplerOad.NOT_STARTED, 0));
            }
            blockWrites++;
            final int per = blockSize - 4;
            final int n = le32(v, 0);
            final int total = (len + per - 1) / per;
            final int expect = n == total - 1 && len % per != 0 ? len % per + 4 : blockSize;
            if (n != next || v.length != expect) {
                reset();
                return one(F91KeplerOad.EXT_CTRL, request(F91KeplerOad.BUFFER_OFL, 0));
            }
            System.arraycopy(v, 4, flash, n * per, v.length - 4);
            if (n == 0) {
                // Header in: check the slot the payload is linked for.
                if (acceptStart >= 0 && payloadStart() != acceptStart) {
                    reset();
                    return one(F91KeplerOad.EXT_CTRL, request(F91KeplerOad.INCOMPATIBLE_IMAGE, 0));
                }
            }
            if (n == corruptBlock) {
                flash[n * per + 1] ^= 0x55;
            }
            next++;
            if (next == total) {
                final CRC32 crc = new CRC32();
                crc.update(flash, 12, len - 12);
                final int want = le32(flash, 8);
                if ((int) crc.getValue() != want) {
                    reset();
                    return one(F91KeplerOad.EXT_CTRL, request(F91KeplerOad.CRC_ERR, 0));
                }
                state = "COMPLETE";
                return one(F91KeplerOad.EXT_CTRL, request(F91KeplerOad.DL_COMPLETE, next));
            }
            return one(F91KeplerOad.EXT_CTRL, request(F91KeplerOad.SUCCESS, next));
        }

        /** The payload segment's start address, from the segments written so far. */
        private long payloadStart() {
            final ByteBuffer b = ByteBuffer.wrap(flash).order(ByteOrder.LITTLE_ENDIAN);
            int at = 44;
            while (flash[at] != 1) {
                at += b.getInt(at + 4);
            }
            return b.getInt(at + 8) & 0xFFFFFFFFL;
        }

        private static List<byte[][]> one(final UUID from, final byte[] v) {
            final List<byte[][]> out = new ArrayList<>();
            out.add(new byte[][]{from.toString().getBytes(StandardCharsets.US_ASCII), v});
            return out;
        }

        private static byte[] request(final int status, final int blk) {
            return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) F91KeplerOad.CMD_BLK_RSP_NOTIF).put((byte) status).putInt(blk).array();
        }
    }

    /**
     * A TI OAD image: fixed header (44 B), optional security segment, payload
     * segment, random code bytes; CRC32 from offset 12, as TI's oad_image_tool does.
     */
    static byte[] image(final int len, final long startAddr, final String softVer, final boolean signed) {
        final ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        b.put("CC26x2R1".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0);                     // crc32, below
        b.put((byte) 3).put((byte) 1);   // bimVer, metaVer
        b.putShort((short) 0xFFFE);      // techType BLE
        b.put((byte) 0xFF).put((byte) 0xFF); // imgCpStat, crcStat
        b.put((byte) F91KeplerOad.IMG_TYPE_APP).put((byte) 1); // imgType, imgNo
        b.putInt(0xFFFFFFFF);            // imgVld
        b.putInt(len);
        b.putInt((int) startAddr + 0x100); // prgEntry
        b.put(softVer.getBytes(StandardCharsets.US_ASCII));
        b.putInt((int) startAddr + len); // imgEndAddr
        b.putShort((short) (signed ? 44 + 0x56 + 12 : 44 + 12)); // hdrLen
        b.putShort((short) 0xFFFF);
        if (signed) {
            final int at = b.position();
            b.put((byte) 3).putShort((short) 0xFFFE).put((byte) 0xFF);
            b.putInt(0x56);
            while (b.position() < at + 0x56) {
                b.put((byte) 0xA5);
            }
        }
        final int payloadAt = b.position();
        b.put((byte) 1).putShort((short) 0xFFFE).put((byte) 0xFF);
        b.putInt(len - payloadAt);
        b.putInt((int) startAddr);
        final byte[] code = new byte[len - b.position()];
        new Random(len ^ startAddr).nextBytes(code);
        b.put(code);
        final byte[] out = b.array();
        fixCrc(out);
        return out;
    }

    static void fixCrc(final byte[] file) {
        final CRC32 crc = new CRC32();
        crc.update(file, 12, file.length - 12);
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putInt(8, (int) crc.getValue());
    }

    private static int le32(final byte[] v, final int at) {
        return ByteBuffer.wrap(v, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void bad(final byte[] file, final String why) {
        try {
            F91KeplerOad.parse(file);
            fail("expected: " + why);
        } catch (final F91KeplerOad.BadImage e) {
            assertTrue(e.getMessage(), e.getMessage().contains(why));
        }
    }
}
