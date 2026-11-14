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

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerOad;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerOadInstallHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerOadInstallItem;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerOadSession;
import nodomain.freeyourgadget.gadgetbridge.install.InstallItem;
import nodomain.freeyourgadget.gadgetbridge.install.InstallSession;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;

/**
 * Runs one {@link F91KeplerOadSession} on the watch link: subscribes to the
 * OAD notifications, queues the session's writes, keeps its stall timer and
 * reports progress and the end to Gadgetbridge's install screen.
 *
 * <p>All session calls happen on the main looper: GATT callbacks are posted
 * there, so the session never sees two events at once.
 */
final class F91KeplerOadRunner implements F91KeplerOadSession.Port {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerOadRunner.class);

    private final AbstractBTLESingleDeviceSupport support;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::onTimeout;

    @Nullable
    private F91KeplerOadSession session;
    @Nullable
    private InstallSession install;
    @Nullable
    private InstallItem item;
    private int lastPercent = -1;
    private long startedAt;

    F91KeplerOadRunner(final AbstractBTLESingleDeviceSupport support) {
        this.support = support;
    }

    /** The watch shows the OAD characteristics on this link. */
    boolean available() {
        return support.getCharacteristic(F91KeplerOad.IMG_BLOCK) != null
                && support.getCharacteristic(F91KeplerOad.EXT_CTRL) != null
                && support.getCharacteristic(F91KeplerOad.IMG_IDENTIFY) != null;
    }

    boolean running() {
        return session != null;
    }

    void start(@NonNull final InstallSession installSession) {
        final Context context = support.getContext();
        final InstallItem first = installSession.getItems().isEmpty() ? null : installSession.getItems().get(0);
        if (!(first instanceof F91KeplerOadInstallItem)) {
            for (final InstallItem i : installSession.getItems()) {
                installSession.failed(i, context.getString(R.string.f91_ota_not_supported));
            }
            return;
        }
        if (running() || !available()) {
            installSession.failed(first, context.getString(R.string.f91_ota_not_supported));
            return;
        }
        // The install screen checked the battery when the file was picked; check again now.
        final String battery = F91KeplerOadInstallHandler.batteryRefusal(context, support.getDevice());
        if (battery != null) {
            installSession.failed(first, battery);
            return;
        }
        final F91KeplerOadInstallItem oadItem = (F91KeplerOadInstallItem) first;
        install = installSession;
        item = first;
        lastPercent = -1;
        startedAt = System.currentTimeMillis();
        session = new F91KeplerOadSession(this, oadItem.getPkg().images);
        LOG.info("F91 OAD: start {} ({} bytes, {} image(s))",
                oadItem.getPkg().version, oadItem.getPkg().size(), oadItem.getPkg().images.size());

        support.getDevice().setBusyTask(R.string.f91_ota_busy, context);
        support.getDevice().sendDeviceUpdateIntent(context);

        // Block writes without response: the watch answers every block with the next
        // request anyway, so a write response would only halve the speed.
        final BluetoothGattCharacteristic block = support.getCharacteristic(F91KeplerOad.IMG_BLOCK);
        if (block != null) {
            block.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        }
        final TransactionBuilder b = support.createTransactionBuilder("oad start");
        b.notify(F91KeplerOad.IMG_IDENTIFY, true);
        b.notify(F91KeplerOad.IMG_BLOCK, true);
        b.notify(F91KeplerOad.EXT_CTRL, true);
        b.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        b.run(() -> handler.post(() -> {
            if (session != null) {
                session.start();
            }
        }));
        b.queue();
    }

    void cancel() {
        handler.post(() -> {
            if (session != null) {
                session.cancel();
            }
        });
    }

    /** A notification from the watch; true when it was one of the OAD characteristics. */
    boolean onNotify(final UUID characteristic, final byte[] value) {
        if (F91KeplerOad.IMG_IDENTIFY.equals(characteristic)) {
            handler.post(() -> {
                if (session != null) {
                    session.onIdentify(value);
                }
            });
            return true;
        }
        if (F91KeplerOad.IMG_BLOCK.equals(characteristic)) {
            // The older OAD profile asked for blocks here; this one asks on the control
            // point (BLK_RSP_NOTIF). Subscribed only because the watch may require it.
            return true;
        }
        if (F91KeplerOad.EXT_CTRL.equals(characteristic)) {
            handler.post(() -> {
                if (session != null) {
                    session.onControl(value);
                }
            });
            return true;
        }
        return false;
    }

    void onLinkLost() {
        handler.post(() -> {
            if (session != null) {
                session.onLinkLost();
            }
        });
    }

    void dispose() {
        handler.removeCallbacksAndMessages(null);
        if (session != null) {
            session.onLinkLost();
        }
    }

    private void onTimeout() {
        if (session != null) {
            session.onTimeout();
        }
    }

    // ---- Port --------------------------------------------------------------

    @Override
    public void write(final UUID characteristic, final byte[] value) {
        final TransactionBuilder b = support.createTransactionBuilder("oad");
        b.write(characteristic, value);
        b.queue();
    }

    @Override
    public void armTimeout(final long ms) {
        handler.removeCallbacks(timeout);
        if (ms > 0) {
            handler.postDelayed(timeout, ms);
        }
    }

    @Override
    public void progress(final int done, final int total) {
        if (install == null || item == null || total <= 0) {
            return;
        }
        final int percent = (int) (100L * done / total);
        if (percent != lastPercent) {
            lastPercent = percent;
            install.progress(item, percent);
        }
    }

    @Override
    public void finished(final F91KeplerOadSession.Result result) {
        final Context context = support.getContext();
        final long seconds = (System.currentTimeMillis() - startedAt) / 1000;
        final F91KeplerOadSession s = session;
        LOG.info("F91 OAD: {} after {} s, {} repeated block(s)", result, seconds, s != null ? s.resends() : 0);
        if (install != null && item != null) {
            switch (result.outcome) {
                case DONE:
                    install.installed(item);
                    break;
                case CANCELLED:
                    install.failed(item, context.getString(R.string.f91_ota_cancelled));
                    break;
                default:
                    final String why = result.status >= 0 ? F91KeplerOad.statusName(result.status) : result.reason;
                    install.failed(item, context.getString(
                            result.retryable ? R.string.f91_ota_failed_retry : R.string.f91_ota_failed, why));
            }
        }
        session = null;
        install = null;
        item = null;
        if (support.getDevice().isBusy()) {
            support.getDevice().unsetBusyTask();
            support.getDevice().sendDeviceUpdateIntent(context);
        }
        if (result.outcome != F91KeplerOadSession.Outcome.DONE && support.isConnected()) {
            final TransactionBuilder b = support.createTransactionBuilder("oad end");
            b.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
            b.queue();
        }
    }
}
