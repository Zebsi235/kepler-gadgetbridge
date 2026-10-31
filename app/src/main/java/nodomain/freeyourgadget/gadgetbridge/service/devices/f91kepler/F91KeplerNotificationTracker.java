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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

    /**
     * How long a notification that nothing will ever dismiss (one that did not
     * come from the notification listener: SMS receiver, intents) keeps its
     * mark and history entry.
     */
    static final long UNTRACKED_MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    private final Set<Integer>[] active;
    /** Untracked ids, with when they were added. */
    private final Map<Integer, Long> untracked = new HashMap<>();

    @SuppressWarnings("unchecked")
    F91KeplerNotificationTracker() {
        active = new Set[Category.values().length];
        for (int i = 0; i < active.length; i++) {
            active[i] = new HashSet<>();
        }
    }

    synchronized void add(final int id, final Category category) {
        untracked.remove(id);
        active[category.ordinal()].add(id);
    }

    /** A notification no dismissal will be reported for: it ends after {@link #UNTRACKED_MAX_AGE_MS}. */
    synchronized void addUntracked(final int id, final Category category, final long nowMillis) {
        add(id, category);
        untracked.put(id, nowMillis);
    }

    synchronized void remove(final int id) {
        untracked.remove(id);
        for (final Set<Integer> set : active) {
            set.remove(id);
        }
    }

    /**
     * Removes the untracked notifications that are too old; returns their ids.
     * Times are {@code SystemClock.elapsedRealtime()}, which never goes back.
     */
    synchronized List<Integer> expire(final long nowMillis) {
        final List<Integer> expired = new ArrayList<>();
        for (final Iterator<Map.Entry<Integer, Long>> it = untracked.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Integer, Long> e = it.next();
            if (nowMillis - e.getValue() >= UNTRACKED_MAX_AGE_MS) {
                it.remove();
                for (final Set<Integer> set : active) {
                    set.remove(e.getKey());
                }
                expired.add(e.getKey());
            }
        }
        return expired;
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
