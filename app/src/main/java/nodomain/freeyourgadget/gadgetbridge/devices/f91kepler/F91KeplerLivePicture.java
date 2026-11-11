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
package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import androidx.annotation.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * "Live pictures": the phone draws a small card -- the next calendar
 * event or the open to-dos -- and sends it to the watch's Image screen like a photo. No firmware
 * change: each refresh is one 480-byte upload, so it replaces the user's own
 * picture. Layouts as in the iPhone app; plain Java, unit-tested on the JVM.
 */
public final class F91KeplerLivePicture {
    /** Characters on the when line of the event card. */
    static final int WHEN_CHARS = 16;

    private F91KeplerLivePicture() {
    }

    /** What the Image screen shows. The values are stored in the preference. */
    public enum Preset {
        OFF("off"), NEXT_EVENT("next_event"), TODO("todo");

        public final String value;

        Preset(final String value) {
            this.value = value;
        }

        public static Preset from(@Nullable final String value) {
            for (final Preset p : values()) {
                if (p.value.equals(value)) {
                    return p;
                }
            }
            return OFF;
        }
    }

    // --- Next calendar event -----------------------------------------------------

    public static final class Event {
        public final String title;
        /** "Today 14:30", "Fri 14 Nov 09:00", "Tmrw all day"; only "14:30" next to a soon tag. */
        public final String when;
        /** "in 25m" when it starts within the hour, else null. */
        @Nullable
        public final String soon;

        public Event(final String title, final String when, @Nullable final String soon) {
            this.title = title;
            this.when = when;
            this.soon = soon;
        }
    }

    /** Labels the caller localizes. */
    public static final class WhenLabels {
        public final String today;
        public final String tomorrow;
        public final String allDay;
        /** Minutes until the start, e.g. "in %dm". */
        public final String soonFormat;

        public WhenLabels(final String today, final String tomorrow, final String allDay,
                          final String soonFormat) {
            this.today = today;
            this.tomorrow = tomorrow;
            this.allDay = allDay;
            this.soonFormat = soonFormat;
        }

        public static final WhenLabels ENGLISH = new WhenLabels("Today", "Tmrw", "all day", "in %dm");
    }

    /**
     * The when line and the soon tag for an event starting at {@code start}.
     * {@code dayFormat} names any other day (the caller picks a short localized
     * pattern such as "EEE d MMM").
     */
    public static Event event(final String title, final Instant start, final boolean allDay,
                              final Instant now, final ZoneId zone, final boolean use24h,
                              final DateTimeFormatter dayFormat, final WhenLabels labels) {
        return event(title, start, allDay, now, zone, use24h, dayFormat, dayFormat, labels);
    }

    /**
     * As above; {@code shortDayFormat} (short, e.g. "d.M.") names the day when
     * {@code dayFormat} and the time would not fit the 16-character line together,
     * so the time is never cut off.
     */
    public static Event event(final String title, final Instant start, final boolean allDay,
                              final Instant now, final ZoneId zone, final boolean use24h,
                              final DateTimeFormatter dayFormat, final DateTimeFormatter shortDayFormat,
                              final WhenLabels labels) {
        final ZonedDateTime s = start.atZone(zone);
        final LocalDate today = now.atZone(zone).toLocalDate();
        String day;
        boolean otherDay = false;
        if (s.toLocalDate().equals(today)) {
            day = labels.today;
        } else if (s.toLocalDate().equals(today.plusDays(1))) {
            day = labels.tomorrow;
        } else {
            day = dayFormat.format(s);
            otherDay = true;
        }
        final String time = allDay
                ? labels.allDay
                : DateTimeFormatter.ofPattern(use24h ? "HH:mm" : "h:mma", Locale.US).format(s)
                        .toLowerCase(Locale.ROOT);
        if (otherDay && day.length() + 1 + time.length() > WHEN_CHARS) {
            day = shortDayFormat.format(s);
        }
        final long minutes = Duration.between(now, start).getSeconds() / 60;
        final boolean soonish = !allDay && !start.isBefore(now) && minutes < 60;
        final String soon = soonish ? String.format(Locale.ROOT, labels.soonFormat, minutes) : null;
        // Within the hour the tag says when; the time alone leaves it room.
        return new Event(title, soon == null ? day + " " + time : time, soon);
    }

    /**
     * When line on top, a dotted rule, the title in up to three lines.
     * {@code null} = nothing coming up: {@code emptyText} in the middle.
     */
    public static F91KeplerLiveCanvas render(@Nullable final Event e, final String emptyText) {
        if (e == null) {
            return centered(emptyText);
        }
        final F91KeplerLiveCanvas c = new F91KeplerLiveCanvas();
        if (e.soon != null) {
            // Right-aligned; the when line gives way so the two never touch.
            final String s = F91KeplerLiveCanvas.fit(e.soon, 6);
            c.text(s, F91KeplerLiveCanvas.WIDTH - F91KeplerLiveCanvas.textWidth(s) + 1, 0);
            c.text(F91KeplerLiveCanvas.fit(e.when, WHEN_CHARS - s.length() - 1), 0, 0);
        } else {
            c.text(F91KeplerLiveCanvas.fit(e.when, WHEN_CHARS), 0, 0);
        }
        c.hline(8, 0, F91KeplerLiveCanvas.WIDTH - 1, true);
        final List<String> lines = F91KeplerLiveCanvas.wrap(e.title, 16, 3);
        for (int i = 0; i < lines.size(); i++) {
            c.text(lines.get(i), 0, 11 + i * 9);
        }
        return c;
    }

    // --- To-do -------------------------------------------------------------------

    /**
     * A header with the count, then up to three open items, each with a square
     * bullet. No items: {@code emptyText} in the middle.
     */
    public static F91KeplerLiveCanvas render(final List<String> todos, final String header,
                                             final String emptyText) {
        if (todos.isEmpty()) {
            return centered(emptyText);
        }
        final F91KeplerLiveCanvas c = new F91KeplerLiveCanvas();
        c.text(F91KeplerLiveCanvas.fit(header, 11), 0, 0);
        final String n = String.valueOf(todos.size());
        c.text(n, F91KeplerLiveCanvas.WIDTH - F91KeplerLiveCanvas.textWidth(n) + 1, 0);
        c.hline(8, 0, F91KeplerLiveCanvas.WIDTH - 1, true);
        for (int i = 0; i < Math.min(3, todos.size()); i++) {
            final int y = 11 + i * 9;
            c.fill(0, y + 2, 3, 3);
            c.text(F91KeplerLiveCanvas.fit(todos.get(i), 15), 5, y);
        }
        return c;
    }

    /** One open to-do: its title and due time (epoch ms, 0 = none). */
    public static final class Todo {
        public final String title;
        public final long due;

        public Todo(final String title, final long due) {
            this.title = title;
            this.due = due;
        }
    }

    /**
     * Due ones first (soonest first), then the rest in the app's own order;
     * blank titles dropped. As in the iPhone app.
     */
    public static List<String> order(final List<Todo> todos) {
        final List<Todo> sorted = new java.util.ArrayList<>(todos);
        // List.sort is stable, so the undated keep their order.
        sorted.sort((a, b) -> {
            if (a.due > 0 && b.due > 0) {
                return Long.compare(a.due, b.due);
            }
            return Boolean.compare(b.due > 0, a.due > 0);
        });
        final List<String> out = new java.util.ArrayList<>();
        for (final Todo t : sorted) {
            if (t.title != null && !t.title.trim().isEmpty()) {
                out.add(t.title.trim());
            }
        }
        return out;
    }

    // --- Shared ------------------------------------------------------------------

    public static F91KeplerLiveCanvas centered(final String text) {
        final F91KeplerLiveCanvas c = new F91KeplerLiveCanvas();
        final List<String> lines = F91KeplerLiveCanvas.wrap(text, 16, 2);
        final int top = (F91KeplerLiveCanvas.HEIGHT - lines.size() * 9 + 2) / 2;
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            c.text(line, (F91KeplerLiveCanvas.WIDTH - F91KeplerLiveCanvas.textWidth(line) + 1) / 2, top + i * 9);
        }
        return c;
    }
}
