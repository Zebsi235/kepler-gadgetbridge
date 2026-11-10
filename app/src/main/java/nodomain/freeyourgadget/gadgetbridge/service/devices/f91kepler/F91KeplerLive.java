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

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.text.format.DateFormat;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerLiveCanvas;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerLivePicture;

/**
 * Draws the chosen live picture from the phone's data. The support class
 * decides when (on connect, then every {@link F91KeplerConstants#LIVE_INTERVAL_MS}
 * while connected, and on Send now) and sends the frame like an uploaded photo.
 */
final class F91KeplerLive {
    private static final Logger LOG = LoggerFactory.getLogger(F91KeplerLive.class);
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;

    /** A reason the picture could not be made, already in the user's words. */
    static final class Failure extends Exception {
        Failure(final String message) {
            super(message);
        }
    }

    private F91KeplerLive() {
    }

    static F91KeplerLivePicture.Preset preset(final SharedPreferences prefs) {
        return F91KeplerLivePicture.Preset.from(
                prefs.getString(F91KeplerConstants.PREF_LIVE_PRESET, F91KeplerLivePicture.Preset.OFF.value));
    }

    /** The card for {@code preset}, or a {@link Failure} to show the user. */
    static F91KeplerLiveCanvas make(final Context context, final F91KeplerLivePicture.Preset preset,
                                    final boolean use24h) throws Failure {
        switch (preset) {
            case NEXT_EVENT:
                return F91KeplerLivePicture.render(nextEvent(context, use24h),
                        context.getString(R.string.f91_live_no_events));
            case OFF:
            default:
                return new F91KeplerLiveCanvas();
        }
    }

    // --- Next calendar event ---------------------------------------------------------

    private static final String[] PROJECTION = {
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
    };

    @Nullable
    private static F91KeplerLivePicture.Event nextEvent(final Context context, final boolean use24h)
            throws Failure {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            throw new Failure(context.getString(R.string.f91_live_allow_calendar));
        }
        final long now = System.currentTimeMillis();
        final Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        // All-day instances are stored at UTC midnight: start the window a day early
        // so one running today is found in every time zone, then filter by its end.
        ContentUris.appendId(uri, now - 24L * 60 * 60 * 1000);
        ContentUris.appendId(uri, now + WEEK_MS);
        final ZoneId zone = ZoneId.systemDefault();
        final long startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        long bestBegin = 0;
        String bestTitle = null;
        long dayBegin = 0;
        String dayTitle = null;
        try (Cursor c = context.getContentResolver().query(uri.build(), PROJECTION,
                CalendarContract.Instances.VISIBLE + " = 1", null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) {
                return null;
            }
            while (c.moveToNext()) {
                final long begin = c.getLong(0);
                final long end = c.getLong(1);
                final String title = c.isNull(2) ? "" : c.getString(2);
                final boolean allDay = c.getInt(3) != 0;
                if (allDay) {
                    // Its dates are UTC: read them as local days.
                    final long localBegin = utcDayToLocal(begin, zone);
                    final long localEnd = utcDayToLocal(end, zone);
                    if (localEnd <= now || localBegin > now + WEEK_MS) {
                        continue;
                    }
                    if (dayTitle == null) {
                        // A running all-day event (several days) reads as today.
                        dayBegin = Math.max(localBegin, startOfToday);
                        dayTitle = title;
                    }
                } else {
                    if (end <= now || begin > now + WEEK_MS) {
                        continue;
                    }
                    if (bestTitle == null) {
                        bestBegin = begin;
                        bestTitle = title;
                    }
                }
            }
        } catch (final SecurityException e) {
            throw new Failure(context.getString(R.string.f91_live_allow_calendar));
        } catch (final RuntimeException e) {
            LOG.warn("F91 live picture: calendar query failed", e);
            return null;
        }
        // A timed event beats an all-day one (a birthday would win all day).
        final boolean allDay = bestTitle == null;
        if (allDay && dayTitle == null) {
            return null;
        }
        final F91KeplerLivePicture.WhenLabels labels = new F91KeplerLivePicture.WhenLabels(
                context.getString(R.string.f91_live_today), context.getString(R.string.f91_live_tomorrow),
                context.getString(R.string.f91_live_all_day), context.getString(R.string.f91_live_soon));
        final Locale locale = Locale.getDefault();
        final DateTimeFormatter dayFormat = DateTimeFormatter.ofPattern(
                DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale);
        final String title = allDay ? dayTitle : bestTitle;
        return F91KeplerLivePicture.event(title == null ? "" : title,
                Instant.ofEpochMilli(allDay ? dayBegin : bestBegin), allDay,
                Instant.ofEpochMilli(now), zone, use24h, dayFormat, labels);
    }

    /** An all-day instant (UTC midnight) as the start of that day in {@code zone}. */
    static long utcDayToLocal(final long utcMillis, final ZoneId zone) {
        final LocalDate day = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate();
        return day.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** Whether the stored image is the last live picture (so Off may remove it). */
    static boolean isLastLiveFrame(final SharedPreferences prefs, @Nullable final byte[] stored) {
        final String mine = prefs.getString(F91KeplerConstants.PREF_LIVE_FRAME, null);
        return stored != null && mine != null && mine.equals(hex(stored));
    }

    static String hex(final byte[] frame) {
        return String.format(Locale.ROOT, "%08x", Arrays.hashCode(frame)) + ":" + frame.length;
    }
}
