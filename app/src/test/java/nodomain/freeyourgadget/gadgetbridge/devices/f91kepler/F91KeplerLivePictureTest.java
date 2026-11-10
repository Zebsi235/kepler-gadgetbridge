package nodomain.freeyourgadget.gadgetbridge.devices.f91kepler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class F91KeplerLivePictureTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = ZonedDateTime.of(2026, 11, 10, 14, 0, 0, 0, BERLIN).toInstant();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private static F91KeplerLivePicture.Event at(final int day, final int hour, final int minute,
                                                 final boolean allDay) {
        final Instant start = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, BERLIN).toInstant();
        return F91KeplerLivePicture.event("Dentist", start, allDay, NOW, BERLIN, true, DAY,
                F91KeplerLivePicture.WhenLabels.ENGLISH);
    }

    // --- Canvas ----------------------------------------------------------------------

    @Test
    public void foldsUmlautsQuotesAndDashes() {
        assertEquals("Muller Strasse - \"ok\"...", F91KeplerLiveCanvas.folded("Müller Straße – „ok“…"));
        assertEquals("5 EUR", F91KeplerLiveCanvas.folded("5 €"));
        assertEquals("", F91KeplerLiveCanvas.folded(null));
    }

    @Test
    public void unknownCharactersDrawAsTheBox() {
        assertArrayEquals(new int[]{'A' - 32, F91KeplerLiveCanvas.BOX}, F91KeplerLiveCanvas.glyphs("A☃"));
    }

    @Test
    public void textWidthCountsTheGap() {
        assertEquals(12, F91KeplerLiveCanvas.textWidth("AB"));
        assertEquals(24, F91KeplerLiveCanvas.textWidth("AB", 2));
    }

    @Test
    public void fitCutsWithADot() {
        assertEquals("Hell.", F91KeplerLiveCanvas.fit("Hello World", 5));
        assertEquals("Hi", F91KeplerLiveCanvas.fit("Hi", 5));
    }

    @Test
    public void wrapBreaksOnWordsAndMarksTheRest() {
        assertEquals(Arrays.asList("one two", "three"), F91KeplerLiveCanvas.wrap("one two three", 7, 3));
        assertEquals(Arrays.asList("one", "two.."), F91KeplerLiveCanvas.wrap("one two three", 5, 2));
        assertEquals(Arrays.asList("abcde", "fg"), F91KeplerLiveCanvas.wrap("abcdefg", 5, 3));
        assertEquals(Collections.emptyList(), F91KeplerLiveCanvas.wrap("   ", 5, 3));
    }

    @Test
    public void drawsTheFirmwareGlyph() {
        final F91KeplerLiveCanvas c = new F91KeplerLiveCanvas();
        c.text("A", 0, 0);
        // 'A' column 0 is 0x7E: row 0 dark, rows 1..6 lit.
        assertFalse(c.isLit(0, 0));
        for (int y = 1; y < 7; y++) {
            assertTrue(c.isLit(0, y));
        }
        assertFalse(c.isLit(5, 3));              // the gap column
    }

    @Test
    public void drawingOutsideIsIgnoredAndPackKeepsTheSize() {
        final F91KeplerLiveCanvas c = new F91KeplerLiveCanvas();
        c.fill(-5, -5, 200, 200);
        assertEquals(F91KeplerConstants.IMAGE_BYTES, c.pack().length);
        assertTrue(c.isLit(95, 38));
        assertFalse(c.isLit(96, 0));
    }

    // --- Next event ------------------------------------------------------------------

    @Test
    public void laterTodayShowsTodayAndTheTime() {
        final F91KeplerLivePicture.Event e = at(10, 16, 30, false);
        assertEquals("Today 16:30", e.when);
        assertNull(e.soon);
    }

    @Test
    public void withinTheHourShowsTheSoonTag() {
        final F91KeplerLivePicture.Event e = at(10, 14, 25, false);
        assertEquals("14:25", e.when);
        assertEquals("in 25m", e.soon);
    }

    @Test
    public void tomorrowAndAllDay() {
        assertEquals("Tmrw 09:00", at(11, 9, 0, false).when);
        assertEquals("Tmrw all day", at(11, 0, 0, true).when);
        assertNull(at(11, 0, 0, true).soon);
    }

    @Test
    public void laterDaysUseTheDayFormat() {
        assertEquals("Fri 13 Nov 09:00", at(13, 9, 0, false).when);
    }

    @Test
    public void runningEventHasNoSoonTag() {
        final F91KeplerLivePicture.Event e = at(10, 13, 30, false);
        assertEquals("Today 13:30", e.when);
        assertNull(e.soon);
    }

    @Test
    public void twelveHourTime() {
        final Instant start = ZonedDateTime.of(2026, 11, 10, 18, 5, 0, 0, BERLIN).toInstant();
        assertEquals("Today 6:05pm", F91KeplerLivePicture.event("x", start, false, NOW, BERLIN, false, DAY,
                F91KeplerLivePicture.WhenLabels.ENGLISH).when);
    }

    @Test
    public void eventCardHasRuleAndTitle() {
        final F91KeplerLiveCanvas c = F91KeplerLivePicture.render(at(10, 16, 30, false), "none");
        // dotted rule on row 8: even columns lit, odd dark
        assertTrue(c.isLit(0, 8));
        assertFalse(c.isLit(1, 8));
        assertTrue(lit(c, 0, 11, 96, 7) > 0);   // first title line
    }

    @Test
    public void soonTagIsRightAligned() {
        final F91KeplerLiveCanvas c = F91KeplerLivePicture.render(at(10, 14, 25, false), "none");
        assertTrue(lit(c, 60, 0, 36, 7) > 0);
    }

    @Test
    public void nothingComingUpIsCentered() {
        final F91KeplerLiveCanvas c = F91KeplerLivePicture.render(null, "No events this week");
        final List<String> lines = F91KeplerLiveCanvas.wrap("No events this week", 16, 2);
        assertEquals(2, lines.size());
        assertEquals(0, lit(c, 0, 0, 96, 10));   // nothing on the top rows
        assertTrue(lit(c, 0, 10, 96, 20) > 0);
    }

    // --- To-do ---------------------------------------------------------------------

    @Test
    public void dueOnesFirstThenTheRestInOrder() {
        final List<String> order = F91KeplerLivePicture.order(Arrays.asList(
                new F91KeplerLivePicture.Todo("milk", 0),
                new F91KeplerLivePicture.Todo("tax", 300),
                new F91KeplerLivePicture.Todo("  ", 50),
                new F91KeplerLivePicture.Todo("call", 100),
                new F91KeplerLivePicture.Todo("bread", 0)));
        assertEquals(Arrays.asList("call", "tax", "milk", "bread"), order);
    }

    @Test
    public void todoCardHasCountRuleAndBullets() {
        final F91KeplerLiveCanvas c = F91KeplerLivePicture.render(
                Arrays.asList("one", "two", "three", "four"), "To do", "All done");
        assertTrue(lit(c, 84, 0, 12, 7) > 0);   // the count, right
        assertTrue(c.isLit(0, 8));               // dotted rule
        assertFalse(c.isLit(1, 8));
        for (int i = 0; i < 3; i++) {
            assertEquals(9, lit(c, 0, 13 + i * 9, 3, 3));   // 3x3 bullet
        }
        assertEquals(0, lit(c, 0, 40, 96, 0));
    }

    @Test
    public void noTodosIsAllDone() {
        final F91KeplerLiveCanvas c = F91KeplerLivePicture.render(Collections.<String>emptyList(),
                "To do", "All done");
        assertEquals(0, lit(c, 0, 0, 96, 10));
        assertTrue(lit(c, 0, 10, 96, 20) > 0);
    }

    @Test
    public void presetFallsBackToOff() {
        assertEquals(F91KeplerLivePicture.Preset.TODO, F91KeplerLivePicture.Preset.from("todo"));
        assertEquals(F91KeplerLivePicture.Preset.NEXT_EVENT, F91KeplerLivePicture.Preset.from("next_event"));
        assertEquals(F91KeplerLivePicture.Preset.OFF, F91KeplerLivePicture.Preset.from("nonsense"));
        assertEquals(F91KeplerLivePicture.Preset.OFF, F91KeplerLivePicture.Preset.from(null));
    }

    private static int lit(final F91KeplerLiveCanvas c, final int x, final int y, final int w, final int h) {
        int n = 0;
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                if (c.isLit(xx, yy)) {
                    n++;
                }
            }
        }
        return n;
    }
}
