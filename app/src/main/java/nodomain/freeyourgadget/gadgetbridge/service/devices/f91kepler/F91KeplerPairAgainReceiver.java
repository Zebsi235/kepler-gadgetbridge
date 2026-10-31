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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.f91kepler;

import android.app.Notification;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.ControlCenterv2;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.BondingUtil;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * "Pair again" for a watch that lost its keys for this phone: drops the phone's
 * stale bond, then reconnects. The watch asks for security on connect, so
 * Android pairs anew (Just Works, a confirmation at most).
 */
public class F91KeplerPairAgainReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerPairAgainReceiver.class);

    static final String EXTRA_ADDRESS = "f91kepler_address";
    /**
     * One notification per watch, in the fork's id range (see
     * GB.NOTIFICATION_ID_MUSIC_ACCESS), so two watches do not replace each
     * other's offer.
     */
    private static final int NOTIFICATION_ID_BASE = 0x4B50_1000;
    private static final long BOND_DROP_WAIT_MS = 8000;

    static void showNotification(final Context context, final GBDevice device) {
        final Intent pairAgain = new Intent(context, F91KeplerPairAgainReceiver.class)
                .putExtra(EXTRA_ADDRESS, device.getAddress());
        final int id = notificationId(device.getAddress());
        // The request code keeps one watch's intent from overwriting the other's.
        final PendingIntent action = PendingIntent.getBroadcast(context, id, pairAgain,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Intent open = new Intent(context, ControlCenterv2.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final String text = context.getString(R.string.f91kepler_pair_again_text, device.getAliasOrName());
        final Notification notification = new NotificationCompat.Builder(context, GB.NOTIFICATION_CHANNEL_ID_DEVICE_WARNINGS)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle(context.getString(R.string.f91kepler_pair_again_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .addAction(0, context.getString(R.string.f91kepler_pair_again_action), action)
                .setAutoCancel(true)
                .build();
        GB.notify(id, notification, context);
    }

    /** The single id used up to v0.94: an offer shown before the update goes too. */
    private static final int OLD_NOTIFICATION_ID = 44;

    static void cancelNotification(final Context context, final String address) {
        GB.removeNotification(notificationId(address), context);
        GB.removeNotification(OLD_NOTIFICATION_ID, context);
    }

    static int notificationId(final String address) {
        return NOTIFICATION_ID_BASE + (address.hashCode() & 0xFFF);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String address = intent.getStringExtra(EXTRA_ADDRESS);
        final GBDevice device = address == null ? null
                : GBApplication.app().getDeviceManager().getDeviceByAddress(address);
        if (device == null) {
            LOG.warn("pair again: unknown device {}", address);
            return;
        }
        cancelNotification(context, address);
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                pairAgain(context, device);
            } finally {
                result.finish();
            }
        }, "f91kepler-pair-again").start();
    }

    private static void pairAgain(final Context context, final GBDevice device) {
        try {
            GBApplication.deviceService(device).disconnect();
            final BluetoothDevice bt = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(device.getAddress());
            if (bt.getBondState() != BluetoothDevice.BOND_NONE) {
                // GB's own unpair: CompanionDeviceManager where available, else removeBond
                final boolean asked = BondingUtil.Unpair(context, device.getAddress());
                LOG.info("pair again: unpair({}) -> {}", device.getAddress(), asked);
                final long until = System.currentTimeMillis() + BOND_DROP_WAIT_MS;
                while (bt.getBondState() != BluetoothDevice.BOND_NONE && System.currentTimeMillis() < until) {
                    Thread.sleep(200);
                }
            }
            LOG.info("pair again: bond state now {}, reconnecting", bt.getBondState());
            GB.toast(context, context.getString(R.string.f91kepler_pair_again_toast), Toast.LENGTH_LONG, GB.INFO);
            GBApplication.deviceService(device).connect();
        } catch (final Exception e) {
            LOG.error("pair again failed", e);
            GB.toast(context, context.getString(R.string.f91kepler_pair_again_failed), Toast.LENGTH_LONG, GB.ERROR);
        }
    }
}
