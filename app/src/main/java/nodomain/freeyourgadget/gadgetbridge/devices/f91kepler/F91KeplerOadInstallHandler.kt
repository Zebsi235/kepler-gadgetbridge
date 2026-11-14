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
package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler

import android.content.Context
import android.net.Uri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.install.Compatibility
import nodomain.freeyourgadget.gadgetbridge.install.InstallHandlerV2
import nodomain.freeyourgadget.gadgetbridge.install.InstallItem
import nodomain.freeyourgadget.gadgetbridge.install.ParsedFiles
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import org.slf4j.LoggerFactory

/**
 * A Kepler firmware update picked by the user: one release zip or one bench
 * image (see [F91KeplerOadPackage]). Offered only for a watch that showed the
 * OAD service, so older watches never see it.
 */
class F91KeplerOadInstallHandler(private val context: Context) : InstallHandlerV2 {

    override fun prepare(files: List<Uri>): ParsedFiles {
        if (files.size != 1) {
            return ParsedFiles.Unsupported(context.getString(R.string.f91_ota_bad_file, "one file at a time"))
        }
        val pkg = try {
            open(context, files[0])
        } catch (e: F91KeplerOad.BadImage) {
            return ParsedFiles.Unsupported(context.getString(R.string.f91_ota_bad_file, e.message))
        }
        return ParsedFiles.Supported(
            listOf(F91KeplerOadInstallItem(pkg, context.getString(R.string.f91_ota_item, pkg.version))),
            context.getString(R.string.f91_ota_info, pkg.version),
            context.getString(R.string.f91_ota_confirm),
        )
    }

    override fun checkCompatibility(parsedFiles: ParsedFiles.Supported, device: GBDevice): Compatibility {
        val pkg = (parsedFiles.items.firstOrNull() as? F91KeplerOadInstallItem)?.pkg
            ?: return Compatibility.Incompatible(context.getString(R.string.f91_ota_not_supported))
        if (!hasOad(device)) {
            return Compatibility.Incompatible(context.getString(R.string.f91_ota_not_supported))
        }
        // Only CC2652R7 boards carry the OAD service, so a watch that has it is one.
        if (!pkg.fits(F91KeplerOadPackage.HARDWARE_REV_A_R7)) {
            return Compatibility.Incompatible(context.getString(R.string.f91_ota_wrong_hardware))
        }
        batteryRefusal(context, device)?.let { return Compatibility.Incompatible(it) }
        return Compatibility.Compatible()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(F91KeplerOadInstallHandler::class.java)

        /** Parse the picked file, or throw [F91KeplerOad.BadImage] with the reason. */
        @JvmStatic
        @Throws(F91KeplerOad.BadImage::class)
        fun open(context: Context, uri: Uri): F91KeplerOadPackage {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw F91KeplerOad.BadImage("cannot open the file")
                return stream.use { F91KeplerOadPackage.read(it) }
            } catch (e: java.io.IOException) {
                LOG.warn("F91 OAD: cannot read {}", uri, e)
                throw F91KeplerOad.BadImage("cannot read the file")
            } catch (e: SecurityException) {
                LOG.warn("F91 OAD: no access to {}", uri, e)
                throw F91KeplerOad.BadImage("cannot read the file")
            }
        }

        @JvmStatic
        fun hasOad(device: GBDevice): Boolean =
            GBApplication.getDeviceSpecificSharedPrefs(device.address)
                .getBoolean(F91KeplerConstants.PREF_OAD_SERVICE, false)

        /** The phone's battery rule as user text, or null when the update may start. */
        @JvmStatic
        fun batteryRefusal(context: Context, device: GBDevice): String? {
            val level = device.getBatteryLevel(0)
            val charging = when (device.getBatteryState(0)) {
                BatteryState.BATTERY_CHARGING, BatteryState.BATTERY_CHARGING_FULL -> true
                else -> false
            }
            F91KeplerOadSession.refusal(level, charging) ?: return null
            return if (level < 0) {
                context.getString(R.string.f91_ota_battery_unknown)
            } else {
                context.getString(R.string.f91_ota_battery_low, level, F91KeplerOadSession.MIN_BATTERY_PERCENT)
            }
        }
    }
}

class F91KeplerOadInstallItem(val pkg: F91KeplerOadPackage, name: String) :
    InstallItem(name, R.drawable.ic_firmware, pkg.size().toLong(), pkg.version)
