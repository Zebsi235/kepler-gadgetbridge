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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * The wire format of TI's OAD profile (service 0xFFC0), as the CC2652R7 watch
 * firmware runs it, and the TI OAD image file the phone sends through it.
 *
 * <p>Taken from the SimpleLink CC13xx/CC26xx SDK 7.10 sources
 * ({@code ble5stack/profiles/oad/cc26xx/oad.c, oad.h},
 * {@code common/cc26xx/oad/oad_image_header.h}); everything is little endian.
 *
 * <p>The exchange the watch expects, all of it driven by the watch:
 * <ol>
 *   <li>Control point {@code GET_BLK_SZ} -> notify {@code [01, size u16]}; the size is the
 *       watch's ATT MTU - 3 (max 244), and each block carries {@code size - 4} image bytes.</li>
 *   <li>Write the 22-byte identify payload to Image Identify -> notify {@code [status]}.</li>
 *   <li>Control point {@code START_OAD} -> notify {@code [12, status, block u32]}: the block the
 *       watch wants next.</li>
 *   <li>Write {@code [block u32][bytes]} to Image Block for exactly that block; the watch
 *       answers each with the next request. A wrong number or length resets it to idle.</li>
 *   <li>After the last block the request says {@code DL_COMPLETE} (CRC checked on the
 *       watch); control point {@code ENABLE_IMG} -> {@code [04, status]}, then it reboots
 *       into the new image.</li>
 * </ol>
 * Because the phone only ever sends the block the watch asks for, a watch that keeps a
 * half-written image and asks for block N after START resumes there with no change here.
 * The stock TI profile does not: a drop or a 10 s pause sends it back to block 0.
 */
public final class F91KeplerOad {
    private F91KeplerOad() {
    }

    private static final String TI_BASE = "-0451-4000-b000-000000000000";
    public static final UUID SERVICE = UUID.fromString("f000ffc0" + TI_BASE);
    public static final UUID IMG_IDENTIFY = UUID.fromString("f000ffc1" + TI_BASE);
    public static final UUID IMG_BLOCK = UUID.fromString("f000ffc2" + TI_BASE);
    public static final UUID EXT_CTRL = UUID.fromString("f000ffc5" + TI_BASE);
    /**
     * OAD reset service of the on-chip layout (1-fw's choice for the CC2652R7): the
     * watch firmware carries only this; writing {@link #RESET_CMD_START_OAD} to it
     * restarts the watch into TI's small "persistent app", which shows the OAD
     * service above, takes the image, and starts the new firmware after ENABLE.
     */
    public static final UUID RESET_SERVICE = UUID.fromString("f000ffd0" + TI_BASE);
    public static final UUID RESET_START = UUID.fromString("f000ffd1" + TI_BASE);
    /** oad_reset_service.h: 0x01 = restart into the persistent app for an update. */
    public static final int RESET_CMD_START_OAD = 0x01;
    /** How long the watch may take to come back as the updater before the update is given up. */
    public static final long UPDATER_RECONNECT_MS = 60_000L;

    // Control point commands (oad.h OAD_EXT_CTRL_*).
    public static final int CMD_GET_BLK_SZ = 0x01;
    public static final int CMD_IMG_CNT = 0x02;
    public static final int CMD_START_OAD = 0x03;
    public static final int CMD_ENABLE_IMG = 0x04;
    public static final int CMD_CANCEL_OAD = 0x05;
    public static final int CMD_DISABLE_BLK_NOTIF = 0x06;
    public static final int CMD_GET_SW_VER = 0x07;
    public static final int CMD_GET_IMG_STAT = 0x08;
    public static final int CMD_GET_PROF_VER = 0x09;
    public static final int CMD_GET_DEV_TYPE = 0x10;
    public static final int CMD_GET_IMG_INFO = 0x11;
    public static final int CMD_BLK_RSP_NOTIF = 0x12;
    public static final int CMD_ERASE_BONDS = 0x13;
    public static final int CMD_NOT_SUPPORTED = 0xFF;

    // Status codes (oad.h OAD_SUCCESS ...).
    public static final int SUCCESS = 0;
    public static final int CRC_ERR = 1;
    public static final int FLASH_ERR = 2;
    public static final int BUFFER_OFL = 3;
    public static final int ALREADY_STARTED = 4;
    public static final int NOT_STARTED = 5;
    public static final int DL_NOT_COMPLETE = 6;
    public static final int NO_RESOURCES = 7;
    public static final int IMAGE_TOO_BIG = 8;
    public static final int INCOMPATIBLE_IMAGE = 9;
    public static final int INVALID_FILE = 10;
    public static final int INCOMPATIBLE_FILE = 11;
    public static final int AUTH_FAIL = 12;
    public static final int EXT_NOT_SUPPORTED = 13;
    public static final int DL_COMPLETE = 14;
    public static final int CCCD_NOT_ENABLED = 15;
    public static final int IMG_ID_TIMEOUT = 16;

    public static final int BLOCK_NUMBER_SIZE = 4;
    public static final int DEFAULT_BLOCK_SIZE = 20;
    public static final int MAX_BLOCK_SIZE = 244;
    /** The MTU that gives the largest block (244 + 3). */
    public static final int WANTED_MTU = MAX_BLOCK_SIZE + 3;
    /** The watch drops an OAD that sees no block for this long (OAD_DEFAULT_INACTIVITY_TIME). */
    public static final long WATCH_INACTIVITY_MS = 10_000L;

    public static final int IMG_TYPE_PERSISTENT_APP = 0;
    public static final int IMG_TYPE_APP = 1;
    public static final int IMG_TYPE_STACK = 2;
    public static final int IMG_TYPE_APP_STACK = 3;
    public static final int IMG_TYPE_APPSTACKLIB = 7;

    static final int SEG_BOUNDARY = 0x00;
    static final int SEG_PAYLOAD = 0x01;
    static final int SEG_SECURITY = 0x03;

    /** imgFixedHdr_t is 44 bytes; the CRC covers the file from bimVer (offset 12) on. */
    static final int FIXED_HEADER_LEN = 44;
    static final int CRC_START = 12;
    public static final int IDENTIFY_LEN = 22;

    /** A short English name for a status code, for logs and the failure text. */
    public static String statusName(final int status) {
        switch (status) {
            case SUCCESS: return "success";
            case CRC_ERR: return "CRC error";
            case FLASH_ERR: return "flash error";
            case BUFFER_OFL: return "wrong block";
            case ALREADY_STARTED: return "already started";
            case NOT_STARTED: return "not started";
            case DL_NOT_COMPLETE: return "download not complete";
            case NO_RESOURCES: return "out of memory";
            case IMAGE_TOO_BIG: return "image too big";
            case INCOMPATIBLE_IMAGE: return "incompatible image";
            case INVALID_FILE: return "not an image for this watch";
            case INCOMPATIBLE_FILE: return "bootloader mismatch";
            case AUTH_FAIL: return "signature check failed";
            case EXT_NOT_SUPPORTED: return "command not supported";
            case DL_COMPLETE: return "download complete";
            case CCCD_NOT_ENABLED: return "notifications off";
            case IMG_ID_TIMEOUT: return "too many refused images";
            default: return "status " + status;
        }
    }

    /** Why a file is not a usable OAD image. */
    public static final class BadImage extends Exception {
        private static final long serialVersionUID = 1L;

        BadImage(final String message) {
            super(message);
        }
    }

    /** One TI OAD image file: the fixed header, its segments, and the bytes to send. */
    public static final class Image {
        public final byte[] bytes;
        public final String imgId;
        public final int crc32;
        public final int bimVer;
        public final int metaVer;
        public final int techType;
        public final int imgType;
        public final int imgNo;
        /** Image length per header; exactly these bytes go to the watch. */
        public final int len;
        public final String softVer;
        public final long imgEndAddr;
        /** Flash address the payload is linked for; -1 without a payload segment. */
        public final long startAddr;
        /** The image carries a security (signature) segment. */
        public final boolean signed;

        private Image(final byte[] bytes, final ByteBuffer b, final long startAddr, final boolean signed) {
            this.bytes = bytes;
            this.imgId = new String(bytes, 0, 8, StandardCharsets.US_ASCII);
            this.crc32 = b.getInt(8);
            this.bimVer = b.get(12) & 0xFF;
            this.metaVer = b.get(13) & 0xFF;
            this.techType = b.getShort(14) & 0xFFFF;
            this.imgType = b.get(18) & 0xFF;
            this.imgNo = b.get(19) & 0xFF;
            this.len = b.getInt(24);
            this.softVer = new String(bytes, 32, 4, StandardCharsets.US_ASCII);
            this.imgEndAddr = b.getInt(36) & 0xFFFFFFFFL;
            this.startAddr = startAddr;
            this.signed = signed;
        }

        /** The 22-byte Image Identify payload (imgIdentifyPld_t) cut from the header. */
        public byte[] identify() {
            final ByteBuffer out = ByteBuffer.allocate(IDENTIFY_LEN).order(ByteOrder.LITTLE_ENDIAN);
            out.put(bytes, 0, 8);    // imgID
            out.put(bytes, 12, 2);   // bimVer, metaVer
            out.put(bytes, 16, 4);   // imgCpStat, crcStat, imgType, imgNo
            out.put(bytes, 24, 4);   // len
            out.put(bytes, 32, 4);   // softVer
            return out.array();
        }

        /** Image bytes carried per block of {@code blockSize} (the block number takes 4). */
        public static int payloadPerBlock(final int blockSize) {
            return blockSize - BLOCK_NUMBER_SIZE;
        }

        public int blockCount(final int blockSize) {
            final int per = payloadPerBlock(blockSize);
            return (len + per - 1) / per;
        }

        /** The write for block {@code n}: {@code [n u32][image bytes]}, the last one short. */
        public byte[] block(final int n, final int blockSize) {
            final int per = payloadPerBlock(blockSize);
            final int from = n * per;
            if (n < 0 || from >= len) {
                throw new IllegalArgumentException("block " + n + " outside the image");
            }
            final int count = Math.min(per, len - from);
            final ByteBuffer out = ByteBuffer.allocate(BLOCK_NUMBER_SIZE + count).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(n);
            out.put(bytes, from, count);
            return out.array();
        }

        /** Software version as "x.y.z" when the four bytes are digits, else as they are. */
        public String versionText() {
            return F91KeplerOad.versionText(softVer);
        }
    }

    /**
     * Whether the DIS firmware string (0x2A26, always the running image) names
     * {@code expected} ("4.0.12"): exact, or with a build suffix ("4.0.12-bl",
     * "4.0.12 rc2"). After a dual-image update a different string means the
     * watch rolled back to the old slot.
     */
    public static boolean runsVersion(@Nullable final String running, @Nullable final String expected) {
        if (running == null || expected == null || expected.trim().isEmpty()) {
            return false;
        }
        final String r = running.trim();
        final String e = expected.trim();
        return r.equals(e) || r.startsWith(e + "-") || r.startsWith(e + " ");
    }

    /** softVer "MmPP" (4 ASCII digits, 1-fw) as "M.m.P"; anything else as it is. */
    public static String versionText(final String softVer) {
        if (softVer == null || !softVer.matches("[0-9]{4}")) {
            return softVer == null ? "" : softVer.trim();
        }
        return softVer.charAt(0) + "." + softVer.charAt(1) + "." + Integer.parseInt(softVer.substring(2));
    }

    /** Parse and check an OAD image: header sane, length matches, CRC right. */
    public static Image parse(final byte[] file) throws BadImage {
        if (file == null || file.length < FIXED_HEADER_LEN) {
            throw new BadImage("too short for an OAD header");
        }
        final ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 8; i++) {
            final int c = file[i] & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                throw new BadImage("no image id");
            }
        }
        final int len = b.getInt(24);
        if (len < FIXED_HEADER_LEN || len > file.length) {
            throw new BadImage("length " + len + " does not fit the file (" + file.length + " bytes)");
        }
        final int hdrLen = b.getShort(40) & 0xFFFF;
        long startAddr = -1;
        boolean signed = false;
        int at = FIXED_HEADER_LEN;
        // Segments: [type u8][tech u16][rfu u8][segLen u32] ...; the payload segment
        // spans the rest of the image, so it is the last one we read.
        while (at + 8 <= len) {
            final int type = file[at] & 0xFF;
            final long segLen = b.getInt(at + 4) & 0xFFFFFFFFL;
            if (type == SEG_PAYLOAD) {
                if (at + 12 > len) {
                    throw new BadImage("cut payload segment");
                }
                startAddr = b.getInt(at + 8) & 0xFFFFFFFFL;
                break;
            }
            if (type == SEG_SECURITY) {
                signed = true;
            } else if (type != SEG_BOUNDARY && type != 0x02) {
                throw new BadImage("unknown segment " + type);
            }
            if (segLen < 8 || at + segLen > len) {
                throw new BadImage("bad segment length");
            }
            at += (int) segLen;
        }
        if (hdrLen != 0 && hdrLen > len) {
            throw new BadImage("header longer than the image");
        }
        final CRC32 crc = new CRC32();
        crc.update(file, CRC_START, len - CRC_START);
        if ((int) crc.getValue() != b.getInt(8)) {
            throw new BadImage("CRC mismatch, the file is damaged");
        }
        return new Image(Arrays.copyOf(file, len), b, startAddr, signed);
    }

    /** The control point notification that asks for the next block. */
    public static final class BlockRequest {
        public final int prevStatus;
        public final int block;

        BlockRequest(final int prevStatus, final int block) {
            this.prevStatus = prevStatus;
            this.block = block;
        }
    }

    @Nullable
    public static BlockRequest blockRequest(final byte[] v) {
        if (v == null || v.length < 6 || (v[0] & 0xFF) != CMD_BLK_RSP_NOTIF) {
            return null;
        }
        return new BlockRequest(v[1] & 0xFF,
                ByteBuffer.wrap(v, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    public static byte[] command(final int cmd) {
        return new byte[]{(byte) cmd};
    }
}
