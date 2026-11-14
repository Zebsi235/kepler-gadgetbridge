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
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerOad;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerOadInstallHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerOadInstallItem;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerOadSession;
import nodomain.freeyourgadget.gadgetbridge.install.InstallItem;
import nodomain.freeyourgadget.gadgetbridge.install.InstallSession;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

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
    private F91KeplerOadInstallItem item;
    private int lastPercent = -1;
    private long startedAt;

    F91KeplerOadRunner(final AbstractBTLESingleDeviceSupport support) {
        this.support = support;
    }

    /**
     * An update that waits for the watch to come back as TI's persistent app. Kept
     * per watch address outside this object: the reconnect may come to a new support.
     */
    private static final class Pending {
        final InstallSession install;
        final F91KeplerOadInstallItem item;

        Pending(final InstallSession install, final F91KeplerOadInstallItem item) {
            this.install = install;
            this.item = item;
        }
    }

    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private final Runnable updaterLate = this::onUpdaterLate;

    /** The watch shows the OAD characteristics on this link (the persistent app does). */
    boolean available() {
        return support.getCharacteristic(F91KeplerOad.IMG_BLOCK) != null
                && support.getCharacteristic(F91KeplerOad.EXT_CTRL) != null
                && support.getCharacteristic(F91KeplerOad.IMG_IDENTIFY) != null;
    }

    /** The watch firmware can restart into the persistent app for an update. */
    boolean canRestartIntoUpdater() {
        return support.getCharacteristic(F91KeplerOad.RESET_START) != null;
    }

    /** Either way this watch takes firmware updates. */
    boolean updatable() {
        return available() || canRestartIntoUpdater();
    }

    /**
     * Only the updater is running: the OAD service is there but none of the watch's
     * own services. That is the persistent app, after a restart for an update or
     * after an image that failed its check (then the watch has no face until a good
     * image arrives).
     */
    boolean updaterOnly() {
        return available() && support.getCharacteristic(F91KeplerConstants.UUID_CHAR_NOTIFICATION_DISMISS) == null
                && support.getCharacteristic(F91KeplerConstants.UUID_CHAR_IMAGE_CHUNK) == null
                && support.getCharacteristic(F91KeplerConstants.UUID_CHAR_MUSIC_CMD) == null;
    }

    boolean running() {
        return session != null;
    }

    /** An update runs or waits for the updater: other bulk writes stay off the link. */
    boolean busy() {
        return running() || PENDING.containsKey(address());
    }

    private String address() {
        return support.getDevice().getAddress().toUpperCase(Locale.ROOT);
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
        final F91KeplerOadInstallItem oadItem = (F91KeplerOadInstallItem) first;
        if (running() || PENDING.containsKey(address()) || !updatable()) {
            installSession.failed(first, context.getString(R.string.f91_ota_not_supported));
            return;
        }
        if (!updaterOnly()) {
            // The install screen checked the battery when the file was picked; check again
            // now. The updater reports no battery, but the watch refuses below 30 % itself.
            final String battery = F91KeplerOadInstallHandler.batteryRefusal(context, support.getDevice());
            if (battery != null) {
                installSession.failed(first, battery);
                return;
            }
        }
        if (available()) {
            begin(installSession, oadItem);
            return;
        }
        // On-chip layout: restart into the persistent app and carry on when it connects.
        LOG.info("F91 OAD: restarting the watch into the updater for {}", oadItem.getPkg().version);
        PENDING.put(address(), new Pending(installSession, oadItem));
        installSession.setExpectingReconnect(true);
        installSession.progress(first, 0);
        support.getDevice().setBusyTask(R.string.f91_ota_restarting, context);
        support.getDevice().sendDeviceUpdateIntent(context);
        handler.postDelayed(updaterLate, F91KeplerOad.UPDATER_RECONNECT_MS);
        final TransactionBuilder b = support.createTransactionBuilder("oad restart into updater");
        b.write(F91KeplerOad.RESET_START, (byte) F91KeplerOad.RESET_CMD_START_OAD);
        b.queue();
    }

    /**
     * The watch connected as the updater (see {@link #updaterOnly}). Carries on the
     * update that restarted it, if this app started one; true when it did.
     */
    boolean onUpdaterConnected() {
        handler.removeCallbacks(updaterLate);
        final Pending pending = PENDING.remove(address());
        if (pending == null) {
            LOG.warn("F91 OAD: the watch runs only its updater and no update is pending");
            return false;
        }
        pending.install.setExpectingReconnect(false);
        begin(pending.install, pending.item);
        return true;
    }

    private void onUpdaterLate() {
        final Pending pending = PENDING.remove(address());
        if (pending == null) {
            return;
        }
        LOG.warn("F91 OAD: the watch did not come back as the updater in {} s",
                F91KeplerOad.UPDATER_RECONNECT_MS / 1000);
        pending.install.setExpectingReconnect(false);
        pending.install.failed(pending.item, support.getContext().getString(R.string.f91_ota_updater_missing));
        clearBusy();
    }

    private void begin(final InstallSession installSession, final F91KeplerOadInstallItem oadItem) {
        final Context context = support.getContext();
        install = installSession;
        item = oadItem;
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
        // A pending restart into the updater keeps its deadline: the update must end
        // with an outcome even when the reconnect comes to a new support object.
        handler.removeCallbacks(timeout);
        if (session != null) {
            session.onLinkLost();
        }
    }

    private void onTimeout() {
        if (session != null) {
            session.onTimeout();
        }
    }

    private void clearBusy() {
        if (support.getDevice().isBusy()) {
            support.getDevice().unsetBusyTask();
            support.getDevice().sendDeviceUpdateIntent(support.getContext());
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

    /**
     * First firmware string after an update: tell the user whether the new
     * image runs or the watch went back to the old one (dual image rollback).
     */
    void checkAfterUpdate(@Nullable final String runningFw) {
        if (runningFw == null) {
            return;
        }
        final SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(address());
        final String expected = prefs.getString(F91KeplerConstants.PREF_OAD_EXPECT, null);
        if (expected == null) {
            return;
        }
        prefs.edit().remove(F91KeplerConstants.PREF_OAD_EXPECT).apply();
        final Context context = support.getContext();
        if (F91KeplerOad.runsVersion(runningFw, expected)) {
            LOG.info("F91 OAD: the watch runs {} as expected", runningFw);
            GB.toast(context, context.getString(R.string.f91_ota_now_runs, expected), Toast.LENGTH_LONG, GB.INFO);
        } else {
            LOG.warn("F91 OAD: expected {}, the watch runs {} (rolled back)", expected, runningFw);
            GB.toast(context, context.getString(R.string.f91_ota_rolled_back, runningFw.trim(), expected),
                    Toast.LENGTH_LONG, GB.WARN);
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
                    GBApplication.getDeviceSpecificSharedPrefs(address()).edit()
                            .putString(F91KeplerConstants.PREF_OAD_EXPECT, item.getPkg().version).apply();
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
        clearBusy();
        if (result.outcome != F91KeplerOadSession.Outcome.DONE && support.isConnected()) {
            final TransactionBuilder b = support.createTransactionBuilder("oad end");
            b.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
            b.queue();
        }
    }
}
