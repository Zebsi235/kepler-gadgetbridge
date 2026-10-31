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
package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerTransfer;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BtLEAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.ReadAction;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.WriteAction;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.notifications.GBProgressNotification;

/**
 * One way to show every transfer from the app to the watch: a notification
 * says what is being sent and counts the writes, and the end is
 * either done (a short message for a deliberate action) or failed (a
 * notification with Try again for what the user started; an automatic
 * transfer, such as the connect-time re-sends, fails quietly and goes again
 * on the next connect). A new transfer gets all of this by going
 * through {@link #queue} or {@link #add}; nothing else to wire up.
 *
 * <p>The markers are actions inside the transfer's own transaction, so a
 * transfer only counts as running while its writes are on the air. The queue
 * stops a transaction at the first refused write and on a dropped link,
 * skipping the closing marker; {@link #onOperationFailed} and
 * {@link #onLinkLost} turn that into the end the user sees.
 *
 * <p>The device is deliberately not marked busy: Gadgetbridge then drops
 * notifications, calls and alarms for it (BUSY_CHECKING), and a phone
 * notification must not get lost because the weather was being sent.
 */
final class F91KeplerTransfers {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerTransfers.class);

    /** A transfer still running after this shows its progress notification; the quick
     *  ones (a time sync, the mark) finish before and never flash one up. */
    private static final long SHOW_PROGRESS_AFTER_MS = 1_000L;
    /** Nothing moved for this long: the transaction was dropped without a callback. */
    private static final long STALL_MS = 30_000L;
    /** Failure notifications, one per kind and watch, far from GB's fixed ids (2..43) and pair-again (44). */
    private static final int FAILED_NOTIFICATION_BASE = 19_100;
    private static final int KINDS = F91KeplerTransfer.values().length;

    /** Fills a transaction with the writes (and reads) of one transfer. */
    interface Body {
        void fill(TransactionBuilder builder);
    }

    private final AbstractBTLESingleDeviceSupport support;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stall = this::onStall;
    private final Runnable showProgress = this::onShowProgress;
    private GBProgressNotification progress;
    /** The progress notification is up for the current transfer. Guarded by this. */
    private boolean progressShown;
    private int currentDone;

    /** The transfer on the air, or null. Guarded by this. */
    private F91KeplerTransfer current;
    private boolean currentByUser;
    private int currentTotal;

    F91KeplerTransfers(final AbstractBTLESingleDeviceSupport support) {
        this.support = support;
    }

    /** A transfer in a transaction of its own. */
    void queue(final String taskName, final F91KeplerTransfer kind, final boolean byUser, final Body body) {
        final TransactionBuilder builder = support.createTransactionBuilder(taskName);
        add(builder, kind, byUser, body);
        builder.queue();
    }

    /**
     * Append a transfer to a transaction: a start marker, the body with a
     * progress marker after each write or read, and (unless the watch confirms
     * it later, see {@link #finish}) a done marker. A body that adds no
     * write is appended as it is, without any of this.
     */
    void add(final TransactionBuilder builder, final F91KeplerTransfer kind, final boolean byUser, final Body body) {
        final TransactionBuilder scratch = support.createTransactionBuilder("transfer body");
        body.fill(scratch);
        final List<BtLEAction> actions = scratch.getTransaction().getActions();
        int total = 0;
        for (final BtLEAction action : actions) {
            if (isTransfer(action)) {
                total++;
            }
        }
        if (total == 0) {
            for (final BtLEAction action : actions) {
                builder.add(action);
            }
            return;
        }
        final int steps = total;
        builder.run(() -> begin(kind, byUser, steps));
        int done = 0;
        for (final BtLEAction action : actions) {
            builder.add(action);
            if (isTransfer(action)) {
                final int now = ++done;
                builder.run(() -> step(kind, now));
            }
        }
        if (kind != F91KeplerTransfer.IMAGE) {
            builder.run(() -> finish(kind, true));
        }
    }

    static boolean isTransfer(final BtLEAction action) {
        return action instanceof WriteAction || action instanceof ReadAction;
    }

    private synchronized void begin(final F91KeplerTransfer kind, final boolean byUser, final int total) {
        if (current != null && current != kind) {
            // Its transaction stopped without a refused write or a drop we heard
            // of (an action the queue could not run): end it quietly, but let a
            // screen waiting for it know it is over.
            final F91KeplerTransfer orphan = current;
            LOG.debug("F91 transfer {} ended without its last step", orphan);
            clear();
            broadcast(orphan, F91KeplerConstants.TRANSFER_STATE_FAILED, 0);
        }
        current = kind;
        currentByUser = byUser;
        currentTotal = total;
        currentDone = 0;
        handler.removeCallbacks(showProgress);
        handler.postDelayed(showProgress, SHOW_PROGRESS_AFTER_MS);
        broadcast(kind, F91KeplerConstants.TRANSFER_STATE_SENDING, 0);
        armStall();
    }

    private synchronized void step(final F91KeplerTransfer kind, final int done) {
        if (current != kind) {
            return;
        }
        currentDone = done;
        if (progressShown) {
            progress.setTotalProgress(done);
        }
        broadcast(kind, F91KeplerConstants.TRANSFER_STATE_SENDING,
                  F91KeplerProtocol.progressPercent(done, currentTotal));
        armStall();
    }

    /**
     * The end of a transfer. Called by the done marker, by the failure paths
     * here, and by the support class for a transfer the watch confirms itself
     * (the image's checksum read). Also for a kind that is not on the air, so a
     * screen waiting for it hears the outcome (an image the watch already held).
     */
    synchronized void finish(final F91KeplerTransfer kind, final boolean ok) {
        final boolean wasCurrent = current == kind;
        final boolean byUser = wasCurrent && currentByUser;
        if (wasCurrent) {
            clear();
        }
        final Context context = support.getContext();
        final String address = support.getDevice().getAddress();
        if (ok) {
            GB.removeNotification(failedNotificationId(kind, address), context);
            if (byUser && kind.toastWhenDone) {
                GB.toast(context, context.getString(kind.doneText), Toast.LENGTH_SHORT, GB.INFO);
            }
            broadcast(kind, F91KeplerConstants.TRANSFER_STATE_DONE, 100);
        } else {
            LOG.warn("F91 transfer {} did not reach the watch", kind);
            // Automatic ones (connect-time re-sends, the weather) would otherwise warn on
            // every reconnect of a watch that keeps refusing; the next connect sends them.
            // The image also fails here when it is not on the air (its own retries ran out).
            if (byUser || (!wasCurrent && kind == F91KeplerTransfer.IMAGE)) {
                showFailed(kind);
            }
            broadcast(kind, F91KeplerConstants.TRANSFER_STATE_FAILED, 0);
        }
    }

    /** A write or read was refused; its transaction stops here. The image has its own retry. */
    synchronized void onOperationFailed() {
        if (current != null && current != F91KeplerTransfer.IMAGE) {
            finish(current, false);
        }
    }

    /** The link dropped. What the next connect sends anyway only counts as interrupted. */
    synchronized void onLinkLost() {
        final F91KeplerTransfer kind = current;
        if (kind == null) {
            return;
        }
        if (kind.resentOnConnect || !currentByUser) {
            LOG.info("F91 transfer {} interrupted by the link, it goes again on connect", kind);
            clear();
            broadcast(kind, F91KeplerConstants.TRANSFER_STATE_INTERRUPTED, 0);
        } else {
            finish(kind, false);
        }
    }

    /** Stored while the watch is away; the next connect sends it. */
    void waiting(final F91KeplerTransfer kind) {
        broadcast(kind, F91KeplerConstants.TRANSFER_STATE_WAITING, 0);
    }

    synchronized boolean isRunning(final F91KeplerTransfer kind) {
        return current == kind;
    }

    synchronized void dispose() {
        if (current != null) {
            clear();
        }
        handler.removeCallbacksAndMessages(null);
    }

    private synchronized void onShowProgress() {
        if (current == null || progressShown) {
            return;
        }
        if (progress == null) {
            progress = new GBProgressNotification(support.getContext(), GB.NOTIFICATION_CHANNEL_ID_TRANSFER);
        }
        progress.start(R.string.f91_transfer_title, current.busyText, currentTotal);
        progress.setTotalProgress(currentDone);
        progressShown = true;
    }

    private void onStall() {
        synchronized (this) {
            if (current != null) {
                LOG.warn("F91 transfer {} made no progress for {} ms", current, STALL_MS);
                finish(current, false);
            }
        }
    }

    private void armStall() {
        handler.removeCallbacks(stall);
        handler.postDelayed(stall, STALL_MS);
    }

    /** Progress notification gone, nothing on the air. */
    private void clear() {
        current = null;
        handler.removeCallbacks(stall);
        handler.removeCallbacks(showProgress);
        if (progressShown) {
            progress.finish();
            progressShown = false;
        }
    }

    private void showFailed(final F91KeplerTransfer kind) {
        final Context context = support.getContext();
        final GBDevice device = support.getDevice();
        final Intent retry = new Intent(context, F91KeplerTransferRetryReceiver.class)
                .putExtra(F91KeplerTransferRetryReceiver.EXTRA_ADDRESS, device.getAddress())
                .putExtra(F91KeplerTransferRetryReceiver.EXTRA_KIND, kind.name());
        final int id = failedNotificationId(kind, device.getAddress());
        final PendingIntent action = PendingIntent.getBroadcast(context, id, retry,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final String text = context.getString(kind.failedText);
        final Notification notification = new NotificationCompat.Builder(context, GB.NOTIFICATION_CHANNEL_ID_DEVICE_WARNINGS)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle(device.getAliasOrName())
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .addAction(0, context.getString(R.string.f91_transfer_retry), action)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        GB.notify(id, notification, context);
    }

    static int failedNotificationId(final F91KeplerTransfer kind, final String address) {
        return FAILED_NOTIFICATION_BASE + (address.hashCode() & 0x3FF) * KINDS + kind.ordinal();
    }

    private void broadcast(final F91KeplerTransfer kind, final int state, final int percent) {
        final Intent intent = new Intent(F91KeplerConstants.ACTION_TRANSFER_STATUS)
                .putExtra(F91KeplerConstants.EXTRA_TRANSFER_ADDRESS, support.getDevice().getAddress())
                .putExtra(F91KeplerConstants.EXTRA_TRANSFER_KIND, kind.name())
                .putExtra(F91KeplerConstants.EXTRA_TRANSFER_STATE, state)
                .putExtra(F91KeplerConstants.EXTRA_TRANSFER_PERCENT, percent);
        LocalBroadcastManager.getInstance(support.getContext()).sendBroadcast(intent);
    }
}
