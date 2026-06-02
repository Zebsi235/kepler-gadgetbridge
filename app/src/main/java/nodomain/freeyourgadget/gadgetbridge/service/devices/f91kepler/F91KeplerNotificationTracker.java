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

import java.util.HashSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;

/**
 * Tracks which notifications are currently active in each of the watch's four
 * bar categories, so the 1-byte bar bitmask reflects "any active notification
 * in this category". Mirrors the companion app's {@code NotificationBarTracker}.
 *
 * A notification is keyed by its Gadgetbridge id (the only handle
 * {@code onDeleteNotification(int)} provides), so posting and removal stay in
 * sync. Each id lives in exactly one category; {@link #remove(int)} drops it
 * from whichever set holds it.
 */
class F91KeplerNotificationTracker {
    enum Category {
        EMAIL(F91KeplerConstants.BIT_EMAIL),
        TEXT(F91KeplerConstants.BIT_TEXT),
        VOICEMAIL(F91KeplerConstants.BIT_VOICEMAIL),
        MISSED_CALL(F91KeplerConstants.BIT_MISSED_CALL);

        final int bit;

        Category(final int bit) {
            this.bit = bit;
        }
    }

    private final Set<Integer>[] active;

    @SuppressWarnings("unchecked")
    F91KeplerNotificationTracker() {
        active = new Set[Category.values().length];
        for (int i = 0; i < active.length; i++) {
            active[i] = new HashSet<>();
        }
    }

    synchronized void add(final int id, final Category category) {
        active[category.ordinal()].add(id);
    }

    synchronized void remove(final int id) {
        for (final Set<Integer> set : active) {
            set.remove(id);
        }
    }

    /** The current bar bitmask: a bit is set iff its category has any active notification. */
    synchronized byte bitmask() {
        int mask = 0;
        for (final Category category : Category.values()) {
            if (!active[category.ordinal()].isEmpty()) {
                mask |= category.bit;
            }
        }
        return (byte) mask;
    }
}
