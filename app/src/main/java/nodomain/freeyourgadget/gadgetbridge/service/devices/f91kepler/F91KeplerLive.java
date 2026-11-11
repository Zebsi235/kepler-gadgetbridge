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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerLiveCanvas;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerLivePicture;
import nodomain.freeyourgadget.gadgetbridge.devices.f91kepler.F91KeplerLiveQuote;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils;
import nodomain.freeyourgadget.gadgetbridge.util.PermissionsUtils;
import nodomain.freeyourgadget.gadgetbridge.util.internethelper.InternetHelperHttpClient;
import nodomain.freeyourgadget.gadgetbridge.util.internethelper.InternetHelperHttpResponse;

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

    /** How often the card may be redrawn: Alpha Vantage's free key allows 25 calls a day. */
    static long interval(final SharedPreferences prefs) {
        if (preset(prefs) == F91KeplerLivePicture.Preset.STOCK) {
            return Math.max(F91KeplerConstants.LIVE_INTERVAL_MS, stockService(prefs).minIntervalMs);
        }
        return F91KeplerConstants.LIVE_INTERVAL_MS;
    }

    static F91KeplerLiveQuote.StockService stockService(final SharedPreferences prefs) {
        return F91KeplerLiveQuote.StockService.from(
                prefs.getString(F91KeplerConstants.PREF_LIVE_STOCK_SERVICE, null));
    }

    /** The card for {@code preset}, or a {@link Failure} to show the user. */
    static F91KeplerLiveCanvas make(final Context context, final SharedPreferences prefs,
                                    final F91KeplerLivePicture.Preset preset,
                                    final boolean use24h) throws Failure {
        switch (preset) {
            case CRYPTO:
                return F91KeplerLiveQuote.render(crypto(context, prefs));
            case STOCK:
                return F91KeplerLiveQuote.render(stock(context, prefs));
            case NEXT_EVENT:
                return F91KeplerLivePicture.render(nextEvent(context, use24h),
                        context.getString(R.string.f91_live_no_events));
            case TODO:
                return F91KeplerLivePicture.render(F91KeplerLivePicture.order(openTodos(context)),
                        context.getString(R.string.f91_live_todo_header),
                        context.getString(R.string.f91_live_all_done));
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
            // Keep the last card rather than claim an empty week.
            LOG.warn("F91 live picture: calendar query failed", e);
            throw new Failure(context.getString(R.string.f91_live_read_failed));
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
        // "Mon, Nov 16" or "Mo., 16. Nov."; without the weekday when the time would not fit.
        final DateTimeFormatter dayFormat = dayPattern(locale, "EEEdMMM", "EEE d MMM");
        final DateTimeFormatter shortDayFormat = dayPattern(locale, "dMMM", "d MMM");
        final String title = allDay ? dayTitle : bestTitle;
        return F91KeplerLivePicture.event(title == null ? "" : title,
                Instant.ofEpochMilli(allDay ? dayBegin : bestBegin), allDay,
                Instant.ofEpochMilli(now), zone, use24h, dayFormat, shortDayFormat, labels);
    }

    private static DateTimeFormatter dayPattern(final Locale locale, final String skeleton,
                                                final String fallback) {
        try {
            return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale);
        } catch (final RuntimeException e) {
            // An ICU pattern java.time cannot read: a plain short one will do.
            LOG.warn("F91 live picture: day pattern not usable, using the default", e);
            return DateTimeFormatter.ofPattern(fallback, locale);
        }
    }

    // --- Prices, through the Internet Helper ------------------------------------------

    private static final int MAX_BODY = 512 * 1024;

    private static F91KeplerLiveQuote.Quote crypto(final Context context, final SharedPreferences prefs)
            throws Failure {
        final F91KeplerLiveQuote.Coin coin = F91KeplerLiveQuote.coin(
                prefs.getString(F91KeplerConstants.PREF_LIVE_COIN, "BTC"));
        if (coin == null) {
            throw new Failure(context.getString(R.string.f91_live_enter_coin));
        }
        final Response r = get(context, coin.url(), Collections.singletonMap("Accept", "application/json"));
        try {
            if (r.status == 429) {
                throw new F91KeplerLiveQuote.QuoteException(F91KeplerLiveQuote.Problem.LIMITED);
            }
            return F91KeplerLiveQuote.decodeCoin(r.body, coin);
        } catch (final F91KeplerLiveQuote.QuoteException e) {
            throw failure(context, prefs, e.problem, r.status);
        }
    }

    private static F91KeplerLiveQuote.Quote stock(final Context context, final SharedPreferences prefs)
            throws Failure {
        final String key = prefs.getString(F91KeplerConstants.PREF_LIVE_STOCK_KEY, "");
        if (key == null || key.trim().isEmpty()) {
            throw new Failure(context.getString(R.string.f91_live_enter_key));
        }
        final String symbol = prefs.getString(F91KeplerConstants.PREF_LIVE_STOCK_SYMBOL, "");
        final F91KeplerLiveQuote.StockService service = stockService(prefs);
        final String url = service.url(symbol, key);
        if (url == null) {
            throw new Failure(context.getString(R.string.f91_live_enter_symbol));
        }
        final Response r = get(context, url, service.headers(key));
        try {
            return service.decode(r.body, symbol == null ? "" : symbol, r.status);
        } catch (final F91KeplerLiveQuote.QuoteException e) {
            throw failure(context, prefs, e.problem, r.status);
        }
    }

    private static Failure failure(final Context context, final SharedPreferences prefs,
                                   final F91KeplerLiveQuote.Problem problem, final int status) {
        switch (problem) {
            case UNKNOWN_SYMBOL:
                return new Failure(context.getString(R.string.f91_live_unknown_symbol));
            case BAD_KEY:
                return new Failure(context.getString(R.string.f91_live_bad_key));
            case LIMITED:
                // The next timed try is 15 minutes (Alpha Vantage: an hour) away anyway.
                return new Failure(context.getString(R.string.f91_live_limited,
                        (int) (interval(prefs) / 60_000L)));
            default:
                return new Failure(status == 200 ? context.getString(R.string.f91_live_unexpected)
                        : context.getString(R.string.f91_live_unreachable_status, status));
        }
    }

    private static final class Response {
        final int status;
        final String body;

        Response(final int status, final String body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * A GET through the Internet Helper, which the user allows under Settings,
     * Internet Helper. Finnhub's key goes in a header; Alpha Vantage only takes
     * it in the URL, which the helper client logs.
     */
    private static Response get(final Context context, final String url,
                                final java.util.Map<String, String> headers) throws Failure {
        if (!GBApplication.getPrefs().getBoolean(F91KeplerConstants.PREF_INTERNETHELPER_ALLOW_LIVE_PRICES, false)) {
            throw new Failure(context.getString(R.string.f91_live_allow_prices));
        }
        if (!AndroidUtils.isPackageInstalled(PermissionsUtils.PACKAGE_INTERNET_HELPER)) {
            // Gadgetbridge's own words: install the helper.
            throw new Failure(InternetUtils.noInternetAccessReason(context));
        }
        if (!PermissionsUtils.checkPermission(context, PermissionsUtils.CUSTOM_PERM_INTERNET_HELPER)) {
            throw new Failure(context.getString(R.string.f91_live_allow_helper));
        }
        try (InternetHelperHttpResponse r = InternetHelperHttpClient.get(url, headers, null, 15_000L)) {
            return new Response(r.getStatus(), read(r.getBody()));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(context.getString(R.string.f91_live_unreachable));
        } catch (final IOException | RuntimeException e) {
            // Its message may hold the URL, and so the key: log the kind only.
            LOG.warn("F91 live picture: price request failed: {}", e.getClass().getSimpleName());
            throw new Failure(context.getString(R.string.f91_live_unreachable));
        }
    }

    private static String read(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) {
                throw new IOException("response too large");
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // --- Open to-dos --------------------------------------------------------------

    private static final String TASKS_ORG_PERMISSION = "org.tasks.permission.READ_TASKS";
    private static final String OPENTASKS_PERMISSION = "org.dmfs.permission.READ_TASKS";
    /** Enough for the count; the card shows three. */
    private static final int MAX_TODOS = 1000;

    /**
     * Open to-dos from Tasks.org (its v0 API, else its older agenda provider),
     * else from OpenTasks. Hidden-until-later and deleted ones are left out.
     */
    private static List<F91KeplerLivePicture.Todo> openTodos(final Context context) throws Failure {
        final PackageManager pm = context.getPackageManager();
        final boolean tasksOrgApi = pm.resolveContentProvider("org.tasks.api", 0) != null;
        final boolean tasksOrgLegacy = pm.resolveContentProvider("org.tasks", 0) != null;
        final boolean openTasks = pm.resolveContentProvider("org.dmfs.tasks", 0) != null;
        if (!tasksOrgApi && !tasksOrgLegacy && !openTasks) {
            throw new Failure(context.getString(R.string.f91_live_no_task_app));
        }
        final boolean tasksOrgAllowed = (tasksOrgApi || tasksOrgLegacy) && allowed(context, TASKS_ORG_PERMISSION);
        final boolean openTasksAllowed = openTasks && allowed(context, OPENTASKS_PERMISSION);
        if (!tasksOrgAllowed && !openTasksAllowed) {
            throw new Failure(context.getString(R.string.f91_live_allow_tasks));
        }
        final long now = System.currentTimeMillis();
        // Each source in turn; one that fails hands over to the next.
        final boolean[] refused = {false};
        List<F91KeplerLivePicture.Todo> found = null;
        if (tasksOrgAllowed && tasksOrgApi) {
            found = tryQuery(context, Uri.parse("content://org.tasks.api/v0/tasks").buildUpon()
                    .appendQueryParameter("completed", "0")
                    .appendQueryParameter("start_before", String.valueOf(now))
                    .appendQueryParameter("sort", "due")
                    .appendQueryParameter("limit", String.valueOf(MAX_TODOS)).build(),
                    new String[]{"title", "due_date"}, null, refused);
        }
        if (found == null && tasksOrgAllowed && tasksOrgLegacy) {
            found = tryQuery(context, Uri.parse("content://org.tasks/todoagenda"),
                    new String[]{"title", "dueDate"},
                    "completed=0 AND deleted=0 AND hideUntil<" + now, refused);
        }
        if (found == null && openTasksAllowed) {
            found = tryQuery(context, Uri.parse("content://org.dmfs.tasks/tasks"),
                    new String[]{"title", "due"}, "is_closed=0 AND _deleted=0 AND visible=1", refused);
        }
        if (found != null) {
            return found;
        }
        // Installed but nothing readable: keep the last card.
        throw new Failure(context.getString(refused[0] ? R.string.f91_live_allow_tasks
                : R.string.f91_live_read_failed));
    }

    @Nullable
    private static List<F91KeplerLivePicture.Todo> tryQuery(final Context context, final Uri uri,
                                                          final String[] projection,
                                                          @Nullable final String selection,
                                                          final boolean[] refused) {
        try {
            return query(context, uri, projection, selection);
        } catch (final SecurityException e) {
            refused[0] = true;
            LOG.warn("F91 live picture: no access to {}", uri.getAuthority());
        } catch (final RuntimeException e) {
            LOG.warn("F91 live picture: task query failed on {}", uri.getAuthority(), e);
        }
        return null;
    }

    /** Title and due (ms, 0 = none) of each row, or null when the provider gave no cursor. */
    @Nullable
    private static List<F91KeplerLivePicture.Todo> query(final Context context, final Uri uri,
                                                       final String[] projection,
                                                       @Nullable final String selection) {
        try (Cursor c = context.getContentResolver().query(uri, projection, selection, null, null)) {
            if (c == null) {
                return null;
            }
            final int title = c.getColumnIndexOrThrow(projection[0]);
            final int due = c.getColumnIndexOrThrow(projection[1]);
            final List<F91KeplerLivePicture.Todo> out = new ArrayList<>();
            while (c.moveToNext() && out.size() < MAX_TODOS) {
                out.add(new F91KeplerLivePicture.Todo(c.isNull(title) ? "" : c.getString(title),
                        c.isNull(due) ? 0 : c.getLong(due)));
            }
            return out;
        }
    }

    private static boolean allowed(final Context context, final String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
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
