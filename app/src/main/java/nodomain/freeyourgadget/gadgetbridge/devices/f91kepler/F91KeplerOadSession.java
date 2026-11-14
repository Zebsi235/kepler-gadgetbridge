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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One firmware update over TI OAD, as a state machine with no Android in it:
 * the device support feeds it notifications and link events, and it answers
 * through {@link Port}. The watch drives the transfer (it names every block it
 * wants); this side only checks, sends and reports.
 *
 * <p>Several images may be offered in order (the slot-A and slot-B links of one
 * release): when the watch refuses one at identify because it is linked for the
 * slot that is running, the next one is tried.
 */
public final class F91KeplerOadSession {

    /** What the session needs from the link. All calls come back on one thread. */
    public interface Port {
        /** Write without response (Image Block, Control Point) or with (Image Identify). */
        void write(UUID characteristic, byte[] value);

        /** (Re)arm the stall timer; {@link #onTimeout} if nothing arrives in time. */
        void armTimeout(long ms);

        void progress(int done, int total);

        /** The update ended: success, or why not. Called exactly once. */
        void finished(Result result);
    }

    public enum Outcome {
        /** The watch took the image and is rebooting into it. */
        DONE,
        /** Refused before anything was sent (battery, file, version). */
        REFUSED,
        /** The watch said no, or the transfer broke; the running firmware is untouched. */
        FAILED,
        /** Stopped by the user. */
        CANCELLED,
    }

    public static final class Result {
        public final Outcome outcome;
        /** A TI status code, or -1. */
        public final int status;
        public final String reason;
        /** The link dropped or stalled mid-transfer: safe to try again from the start. */
        public final boolean retryable;
        /** The watch refused at identify because its battery is below its floor (1-fw: status 7). */
        public final boolean watchBatteryLow;
        /** The watch refused at identify: the image is not newer than the running one (1-fw: status 11). */
        public final boolean notNewer;

        Result(final Outcome outcome, final int status, final String reason, final boolean retryable) {
            this(outcome, status, reason, retryable, false, false);
        }

        Result(final Outcome outcome, final int status, final String reason, final boolean retryable,
               final boolean watchBatteryLow, final boolean notNewer) {
            this.outcome = outcome;
            this.status = status;
            this.reason = reason;
            this.retryable = retryable;
            this.watchBatteryLow = watchBatteryLow;
            this.notNewer = notNewer;
        }

        @Override
        public String toString() {
            return outcome + (status >= 0 ? " (" + F91KeplerOad.statusName(status) + ")" : "") + ": " + reason;
        }
    }

    /** No answer for this long ends the update; the watch itself gives up after 10 s. */
    public static final long STALL_MS = 12_000L;
    /** Below this the phone does not start; the watch firmware may refuse on its own too. */
    public static final int MIN_BATTERY_PERCENT = 30;

    enum State { IDLE, BLOCK_SIZE, VERSION, IDENTIFY, START, DOWNLOAD, ENABLE, ENDED }

    private final Port port;
    private final List<F91KeplerOad.Image> images;
    private State state = State.IDLE;
    private int imageIndex;
    private int blockSize = F91KeplerOad.DEFAULT_BLOCK_SIZE;
    private int blocksTotal;
    private int highestSent = -1;
    private int resends;
    /** The running image's version from GET_SW_VER ("M.m.r"), or null. */
    private String runningVersion;

    public F91KeplerOadSession(final Port port, final List<F91KeplerOad.Image> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("no image");
        }
        this.port = port;
        this.images = new ArrayList<>(images);
    }

    /**
     * Phone-side checks before the first byte goes out. {@code battery} is the
     * watch's level in percent, or -1 when unknown (then: refuse; an update on a
     * flat cell is the one thing that can leave a watch without firmware).
     */
    public static String refusal(final int battery, final boolean charging) {
        if (battery < 0) {
            return "battery level unknown";
        }
        if (battery < MIN_BATTERY_PERCENT && !charging) {
            return "battery " + battery + " % is below " + MIN_BATTERY_PERCENT + " %";
        }
        return null;
    }

    State state() {
        return state;
    }

    public F91KeplerOad.Image image() {
        return images.get(imageIndex);
    }

    /** The version the watch runs, as it answered GET_SW_VER; null if not asked or no answer. */
    public String runningVersion() {
        return runningVersion;
    }

    public int blockSize() {
        return blockSize;
    }

    /** Begin: ask the watch for its block size. Notifications must be on already. */
    public void start() {
        if (state != State.IDLE) {
            return;
        }
        state = State.BLOCK_SIZE;
        port.armTimeout(STALL_MS);
        port.write(F91KeplerOad.EXT_CTRL, F91KeplerOad.command(F91KeplerOad.CMD_GET_BLK_SZ));
    }

    public void cancel() {
        if (state == State.ENDED || state == State.IDLE) {
            return;
        }
        port.write(F91KeplerOad.EXT_CTRL, F91KeplerOad.command(F91KeplerOad.CMD_CANCEL_OAD));
        end(Outcome.CANCELLED, -1, "cancelled", false);
    }

    /** A notification on Image Identify: {@code [status]}. */
    public void onIdentify(final byte[] value) {
        if (state != State.IDENTIFY || value == null || value.length < 1) {
            return;
        }
        final int status = value[0] & 0xFF;
        if (status == F91KeplerOad.SUCCESS) {
            state = State.START;
            port.armTimeout(STALL_MS);
            port.write(F91KeplerOad.EXT_CTRL, F91KeplerOad.command(F91KeplerOad.CMD_START_OAD));
            return;
        }
        // Linked for the running slot, or too big for this layout: try the other link.
        if (slotMismatch(status) && imageIndex + 1 < images.size()) {
            imageIndex++;
            identify();
            return;
        }
        // 1-fw's answers at identify (watch side after 51c1cce); none of them tries the other image.
        switch (status) {
            case F91KeplerOad.NO_RESOURCES:          // battery below 30 %
                endRefusal(new Result(Outcome.FAILED, status, "the watch battery is too low", false, true, false));
                return;
            case F91KeplerOad.INCOMPATIBLE_FILE:     // not newer than the running image
                endRefusal(new Result(Outcome.FAILED, status, "the watch already runs this or a newer version",
                        false, false, true));
                return;
            case F91KeplerOad.FLASH_ERR:             // running image not confirmed yet
                end(Outcome.FAILED, status, "the watch is not ready for an update yet", true);
                return;
            default:
                end(Outcome.FAILED, status, "the watch refused the image", false);
        }
    }

    private void endRefusal(final Result result) {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        port.armTimeout(0);
        port.finished(result);
    }

    /** A notification on the control point. */
    public void onControl(final byte[] value) {
        if (state == State.ENDED || value == null || value.length < 1) {
            return;
        }
        final int cmd = value[0] & 0xFF;
        switch (cmd) {
            case F91KeplerOad.CMD_GET_BLK_SZ:
                if (state == State.BLOCK_SIZE && value.length >= 3) {
                    final int size = (value[1] & 0xFF) | (value[2] & 0xFF) << 8;
                    if (size <= F91KeplerOad.BLOCK_NUMBER_SIZE || size > F91KeplerOad.MAX_BLOCK_SIZE) {
                        end(Outcome.FAILED, -1, "watch block size " + size, false);
                        return;
                    }
                    blockSize = size;
                    if (image().mcuboot) {
                        askVersion();
                    } else {
                        identify();
                    }
                }
                return;
            case F91KeplerOad.CMD_GET_SW_VER:
                if (state == State.VERSION) {
                    runningVersion = F91KeplerOad.runningVersion(value);
                    if (runningVersion != null && !F91KeplerOad.isNewer(image().versionText(), runningVersion)) {
                        // The watch would refuse at identify (status 11); say so before anything is sent.
                        endRefusal(new Result(Outcome.FAILED, -1, "the watch already runs " + runningVersion,
                                false, false, true));
                        return;
                    }
                    identify();
                }
                return;
            case F91KeplerOad.CMD_BLK_RSP_NOTIF:
                onBlockRequest(F91KeplerOad.blockRequest(value));
                return;
            case F91KeplerOad.CMD_ENABLE_IMG:
                if (state == State.ENABLE) {
                    final int status = value.length >= 2 ? value[1] & 0xFF : -1;
                    if (status == F91KeplerOad.SUCCESS) {
                        end(Outcome.DONE, status, "the watch restarts with the new firmware", false);
                    } else {
                        end(Outcome.FAILED, status, "the watch did not switch to the new image", false);
                    }
                }
                return;
            case F91KeplerOad.CMD_START_OAD:
                // Only sent back on a refusal; success answers with a block request.
                if (state == State.START && value.length >= 2) {
                    end(Outcome.FAILED, value[1] & 0xFF, "the watch did not start the update", true);
                }
                return;
            default:
                // Other command answers (version, device type) are not part of the transfer.
        }
    }

    private static boolean slotMismatch(final int status) {
        return status == F91KeplerOad.INCOMPATIBLE_IMAGE || status == F91KeplerOad.IMAGE_TOO_BIG;
    }

    /** Errors of the transfer itself (air, flash write, memory): the same file may well work next time. */
    private static boolean transient_(final int status) {
        switch (status) {
            case F91KeplerOad.CRC_ERR:
            case F91KeplerOad.FLASH_ERR:
            case F91KeplerOad.BUFFER_OFL:
            case F91KeplerOad.NO_RESOURCES:
            case F91KeplerOad.NOT_STARTED:
            case F91KeplerOad.DL_NOT_COMPLETE:
                return true;
            default:
                return false;
        }
    }

    private boolean identifyRetried;

    /**
     * Ask which version runs (1-fw: DIS 0x2A26 on the R7 is compiled in and
     * wrong, GET_SW_VER is the image header the watch compares at identify).
     */
    private void askVersion() {
        state = State.VERSION;
        port.armTimeout(STALL_MS);
        port.write(F91KeplerOad.EXT_CTRL, F91KeplerOad.command(F91KeplerOad.CMD_GET_SW_VER));
    }

    private void identify() {
        state = State.IDENTIFY;
        blocksTotal = image().blockCount(blockSize);
        highestSent = -1;
        port.armTimeout(STALL_MS);
        port.write(F91KeplerOad.IMG_IDENTIFY, image().identify());
    }

    private void onBlockRequest(final F91KeplerOad.BlockRequest req) {
        if (req == null || (state != State.START && state != State.DOWNLOAD)) {
            return;
        }
        if (req.prevStatus == F91KeplerOad.DL_COMPLETE) {
            port.progress(blocksTotal, blocksTotal);
            state = State.ENABLE;
            port.armTimeout(STALL_MS);
            port.write(F91KeplerOad.EXT_CTRL, F91KeplerOad.command(F91KeplerOad.CMD_ENABLE_IMG));
            return;
        }
        if (req.prevStatus != F91KeplerOad.SUCCESS) {
            // The watch has reset its download and keeps running the old firmware.
            // It checks the full header (boundary, slot) only once the header blocks
            // are in, so a slot mismatch can surface here rather than at identify.
            if (slotMismatch(req.prevStatus) && imageIndex + 1 < images.size()) {
                imageIndex++;
                identify();
                return;
            }
            end(Outcome.FAILED, req.prevStatus, "the watch stopped the download", transient_(req.prevStatus));
            return;
        }
        if (req.block < 0 || req.block >= blocksTotal) {
            end(Outcome.FAILED, -1, "the watch asked for block " + req.block + " of " + blocksTotal, true);
            return;
        }
        if (req.block <= highestSent) {
            resends++;
        }
        highestSent = Math.max(highestSent, req.block);
        state = State.DOWNLOAD;
        port.armTimeout(STALL_MS);
        port.write(F91KeplerOad.IMG_BLOCK, image().block(req.block, blockSize));
        port.progress(req.block, blocksTotal);
    }

    /** Blocks the watch asked for twice (a sign of a poor link). */
    public int resends() {
        return resends;
    }

    public void onTimeout() {
        if (state == State.ENDED || state == State.IDLE) {
            return;
        }
        if (state == State.VERSION) {
            // No answer to the version query: the watch still checks the version at identify.
            identify();
            return;
        }
        if (state == State.IDENTIFY && !identifyRetried) {
            // 1-fw: right after a quick reconnect the watch may drop the first identify unanswered.
            identifyRetried = true;
            identify();
            return;
        }
        end(Outcome.FAILED, -1, "no answer from the watch in " + (STALL_MS / 1000) + " s", true);
    }

    public void onLinkLost() {
        if (state == State.ENDED || state == State.IDLE) {
            return;
        }
        if (state == State.ENABLE) {
            // The reboot can beat the answer to ENABLE_IMG; the reconnect tells which image runs.
            end(Outcome.DONE, -1, "the link dropped while the watch switched images", false);
            return;
        }
        end(Outcome.FAILED, -1, "the connection dropped", true);
    }

    private void end(final Outcome outcome, final int status, final String reason, final boolean retryable) {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        port.armTimeout(0);
        port.finished(new Result(outcome, status, reason, retryable));
    }
}
