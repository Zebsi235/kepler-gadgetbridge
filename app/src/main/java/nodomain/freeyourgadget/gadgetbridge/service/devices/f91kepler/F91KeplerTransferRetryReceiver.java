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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerTransfer;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** "Try again" on a failed transfer's notification: asks the watch's support to send that kind anew. */
public class F91KeplerTransferRetryReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerTransferRetryReceiver.class);

    static final String EXTRA_ADDRESS = "f91kepler_address";
    static final String EXTRA_KIND = "f91kepler_transfer";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String address = intent.getStringExtra(EXTRA_ADDRESS);
        final String kindName = intent.getStringExtra(EXTRA_KIND);
        final GBDevice device = address == null ? null
                : GBApplication.app().getDeviceManager().getDeviceByAddress(address);
        final F91KeplerTransfer kind;
        try {
            kind = F91KeplerTransfer.valueOf(String.valueOf(kindName));
        } catch (final IllegalArgumentException e) {
            LOG.warn("retry: unknown transfer {}", kindName);
            return;
        }
        if (device == null) {
            LOG.warn("retry: unknown device {}", address);
            return;
        }
        if (!device.isInitialized()) {
            // The notification stays, so it can be tapped again once the watch is back.
            GB.toast(context, context.getString(R.string.f91_transfer_retry_offline), Toast.LENGTH_LONG, GB.INFO);
            return;
        }
        GB.removeNotification(F91KeplerTransfers.failedNotificationId(kind, address), context);
        GBApplication.deviceService(device)
                .onSendConfiguration(F91KeplerConstants.PREF_TRANSFER_RETRY_PREFIX + kind.name());
    }
}
