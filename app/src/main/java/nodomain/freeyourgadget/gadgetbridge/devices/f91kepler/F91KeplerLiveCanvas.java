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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * A 96x39 1-bit drawing surface for the live pictures, with the watch's own
 * 5x7 font (font_5x7.h in the firmware, MIT), so text the phone draws looks
 * like text the watch draws. Plain Java, no Android: unit-tested on the JVM.
 *
 * <p>Orientation as in {@link F91KeplerImageCodec#packImage}: index
 * {@code x + y * WIDTH}, y = 0 is the top. Same layout rules as the iPhone app.
 */
public final class F91KeplerLiveCanvas {
    public static final int WIDTH = F91KeplerConstants.IMAGE_WIDTH;
    public static final int HEIGHT = F91KeplerConstants.IMAGE_HEIGHT;
    /** Glyph cell: 5 columns + 1 column gap; 7 rows. */
    public static final int GLYPH_WIDTH = 6;
    public static final int GLYPH_HEIGHT = 7;
    /** Font index of the box drawn for a character the font lacks. */
    static final int BOX = 95;

    private final boolean[] lit = new boolean[WIDTH * HEIGHT];

    public boolean[] lit() {
        return lit.clone();
    }

    public byte[] pack() {
        return F91KeplerImageCodec.packImage(lit);
    }

    public void set(final int x, final int y) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) {
            return;
        }
        lit[x + y * WIDTH] = true;
    }

    public boolean isLit(final int x, final int y) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) {
            return false;
        }
        return lit[x + y * WIDTH];
    }

    // --- Text ------------------------------------------------------------------

    /**
     * Draws {@code text} with its top-left at (x, y), {@code scale} pixels per
     * font pixel. Characters the font lacks are folded to ASCII or drawn as the
     * firmware's box. Returns the x after the last glyph.
     */
    public int text(final String text, final int x, final int y, final int scale) {
        int cx = x;
        for (final int g : glyphs(text)) {
            final int[] cols = FONT[g];
            for (int c = 0; c < cols.length; c++) {
                for (int r = 0; r < GLYPH_HEIGHT; r++) {
                    if ((cols[c] & (1 << r)) != 0) {
                        fill(cx + c * scale, y + r * scale, scale, scale);
                    }
                }
            }
            cx += GLYPH_WIDTH * scale;
        }
        return cx;
    }

    public int text(final String text, final int x, final int y) {
        return text(text, x, y, 1);
    }

    /** Pixel width of {@code text} as {@link #text} draws it (gap included). */
    public static int textWidth(final String text, final int scale) {
        return glyphs(text).length * GLYPH_WIDTH * scale;
    }

    public static int textWidth(final String text) {
        return textWidth(text, 1);
    }

    /** Cuts {@code text} to {@code max} characters, ending in "." when cut. */
    public static String fit(final String text, final int max) {
        final int[] cps = folded(text).codePoints().toArray();
        if (cps.length <= max || max <= 0) {
            return new String(cps, 0, cps.length);
        }
        return new String(cps, 0, max - 1) + ".";
    }

    /**
     * Word-wraps {@code text} into at most {@code lines} lines of {@code max}
     * characters; the last line ends in ".." when the text is longer.
     */
    public static List<String> wrap(final String text, final int max, final int lines) {
        final List<String> out = new ArrayList<>();
        String line = "";
        for (final String word : folded(text).split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String w = word;
            if (length(w) > max) {                     // a word longer than a line
                if (!line.isEmpty()) {
                    out.add(line);
                    line = "";
                }
                while (length(w) > max) {
                    out.add(prefix(w, max));
                    w = w.substring(w.offsetByCodePoints(0, max));
                }
            }
            if (line.isEmpty()) {
                line = w;
            } else if (length(line) + 1 + length(w) <= max) {
                line = line + " " + w;
            } else {
                out.add(line);
                line = w;
            }
        }
        if (!line.isEmpty()) {
            out.add(line);
        }
        if (out.size() <= lines) {
            return out;
        }
        final List<String> kept = new ArrayList<>(out.subList(0, lines));
        final String last = kept.get(lines - 1);
        kept.set(lines - 1, (length(last) <= max - 2 ? last : prefix(last, max - 2)) + "..");
        return kept;
    }

    private static int length(final String s) {
        return s.codePointCount(0, s.length());
    }

    private static String prefix(final String s, final int n) {
        return s.substring(0, s.offsetByCodePoints(0, Math.min(n, length(s))));
    }

    // --- Shapes ----------------------------------------------------------------

    public void fill(final int x, final int y, final int w, final int h) {
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                set(xx, yy);
            }
        }
    }

    public void hline(final int y, final int x0, final int x1, final boolean dotted) {
        for (int x = x0; x <= x1; x++) {
            if (!dotted || x % 2 == 0) {
                set(x, y);
            }
        }
    }

    /** Bresenham line, both ends included. */
    public void line(final int x0, final int y0, final int x1, final int y1) {
        int x = x0;
        int y = y0;
        final int dx = Math.abs(x1 - x0);
        final int dy = -Math.abs(y1 - y0);
        final int sx = x0 < x1 ? 1 : -1;
        final int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            set(x, y);
            if (x == x1 && y == y1) {
                return;
            }
            final int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    /**
     * {@code values} as a connected line in the box (x, y, w, h), oldest left,
     * scaled to their own min..max. One value or a flat series draws a line
     * through the middle.
     */
    public void sparkline(final double[] values, final int x, final int y, final int w, final int h) {
        final List<Double> v = new ArrayList<>();
        for (final double d : values) {
            if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                v.add(d);
            }
        }
        if (v.isEmpty() || w <= 1 || h <= 1) {
            return;
        }
        double lo = v.get(0);
        double hi = v.get(0);
        for (final double d : v) {
            lo = Math.min(lo, d);
            hi = Math.max(hi, d);
        }
        if (v.size() == 1) {
            hline(py(v.get(0), lo, hi, y, h), x, x + w - 1, false);
            return;
        }
        int px0 = -1;
        int py0 = -1;
        for (int i = 0; i < v.size(); i++) {
            final int px = x + (int) Math.round((double) i / (v.size() - 1) * (w - 1));
            final int p = py(v.get(i), lo, hi, y, h);
            if (i > 0) {
                line(px0, py0, px, p);
            } else {
                set(px, p);
            }
            px0 = px;
            py0 = p;
        }
    }

    private static int py(final double value, final double lo, final double hi, final int y, final int h) {
        if (!(hi > lo)) {
            return y + (h - 1) / 2;
        }
        return y + (h - 1) - (int) Math.round((value - lo) / (hi - lo) * (h - 1));
    }

    // --- Font ------------------------------------------------------------------

    /**
     * Font indices of {@code text}, one per code point: ASCII 32..126 map to
     * 0..94, anything else is folded first and then drawn as the box (95).
     */
    static int[] glyphs(final String text) {
        return folded(text).codePoints().map(cp -> cp >= 32 && cp <= 126 ? cp - 32 : BOX).toArray();
    }

    /**
     * The text as the font can draw it: umlauts and accents dropped, typographic
     * quotes and dashes made plain, other characters kept (they draw as the box).
     */
    public static String folded(final String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("ß", "ss")
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"').replace('„', '"')
                .replace('–', '-').replace('—', '-')
                .replace("…", "...").replace("€", "EUR")
                .replace('\n', ' ').replace('\t', ' ').replace('\r', ' ');
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** 96 glyphs, 5 columns each, bit 0 = top row (firmware font_5x7.h). */
    static final int[][] FONT = {
            {0x00, 0x00, 0x00, 0x00, 0x00}, {0x00, 0x00, 0x4F, 0x00, 0x00}, {0x00, 0x07, 0x00, 0x07, 0x00}, {0x14, 0x7F, 0x14, 0x7F, 0x14},
            {0x24, 0x2A, 0x7F, 0x2A, 0x12}, {0x23, 0x13, 0x08, 0x64, 0x62}, {0x36, 0x49, 0x55, 0x22, 0x50}, {0x00, 0x05, 0x03, 0x00, 0x00},
            {0x00, 0x1C, 0x22, 0x41, 0x00}, {0x00, 0x41, 0x22, 0x1C, 0x00}, {0x14, 0x08, 0x3E, 0x08, 0x14}, {0x08, 0x08, 0x3E, 0x08, 0x08},
            {0x00, 0x50, 0x30, 0x00, 0x00}, {0x08, 0x08, 0x08, 0x08, 0x08}, {0x00, 0x60, 0x60, 0x00, 0x00}, {0x20, 0x10, 0x08, 0x04, 0x02},
            {0x3E, 0x51, 0x49, 0x45, 0x3E}, {0x00, 0x42, 0x7F, 0x40, 0x00}, {0x42, 0x61, 0x51, 0x49, 0x46}, {0x21, 0x41, 0x45, 0x4B, 0x31},
            {0x18, 0x14, 0x12, 0x7F, 0x10}, {0x27, 0x49, 0x49, 0x49, 0x31}, {0x3C, 0x4A, 0x49, 0x49, 0x30}, {0x01, 0x71, 0x09, 0x05, 0x03},
            {0x36, 0x49, 0x49, 0x49, 0x36}, {0x06, 0x49, 0x49, 0x29, 0x1E}, {0x00, 0x36, 0x36, 0x00, 0x00}, {0x00, 0x56, 0x36, 0x00, 0x00},
            {0x08, 0x14, 0x22, 0x41, 0x00}, {0x14, 0x14, 0x14, 0x14, 0x14}, {0x00, 0x41, 0x22, 0x14, 0x08}, {0x02, 0x01, 0x51, 0x09, 0x06},
            {0x32, 0x49, 0x79, 0x41, 0x3E}, {0x7E, 0x11, 0x11, 0x11, 0x7E}, {0x7F, 0x49, 0x49, 0x49, 0x36}, {0x3E, 0x41, 0x41, 0x41, 0x22},
            {0x7F, 0x41, 0x41, 0x22, 0x1C}, {0x7F, 0x49, 0x49, 0x49, 0x41}, {0x7F, 0x09, 0x09, 0x09, 0x01}, {0x3E, 0x41, 0x49, 0x49, 0x7A},
            {0x7F, 0x08, 0x08, 0x08, 0x7F}, {0x00, 0x41, 0x7F, 0x41, 0x00}, {0x20, 0x40, 0x41, 0x3F, 0x01}, {0x7F, 0x08, 0x14, 0x22, 0x41},
            {0x7F, 0x40, 0x40, 0x40, 0x40}, {0x7F, 0x02, 0x0C, 0x02, 0x7F}, {0x7F, 0x04, 0x08, 0x10, 0x7F}, {0x3E, 0x41, 0x41, 0x41, 0x3E},
            {0x7F, 0x09, 0x09, 0x09, 0x06}, {0x3E, 0x41, 0x51, 0x21, 0x5E}, {0x7F, 0x09, 0x19, 0x29, 0x46}, {0x46, 0x49, 0x49, 0x49, 0x31},
            {0x01, 0x01, 0x7F, 0x01, 0x01}, {0x3F, 0x40, 0x40, 0x40, 0x3F}, {0x1F, 0x20, 0x40, 0x20, 0x1F}, {0x3F, 0x40, 0x38, 0x40, 0x3F},
            {0x63, 0x14, 0x08, 0x14, 0x63}, {0x07, 0x08, 0x70, 0x08, 0x07}, {0x61, 0x51, 0x49, 0x45, 0x43}, {0x7F, 0x41, 0x41, 0x00, 0x00},
            {0x02, 0x04, 0x08, 0x10, 0x20}, {0x00, 0x41, 0x41, 0x7F, 0x00}, {0x04, 0x02, 0x01, 0x02, 0x04}, {0x40, 0x40, 0x40, 0x40, 0x40},
            {0x00, 0x01, 0x02, 0x04, 0x00}, {0x20, 0x54, 0x54, 0x54, 0x78}, {0x7F, 0x48, 0x44, 0x44, 0x38}, {0x38, 0x44, 0x44, 0x44, 0x20},
            {0x38, 0x44, 0x44, 0x48, 0x7F}, {0x38, 0x54, 0x54, 0x54, 0x18}, {0x08, 0x7E, 0x09, 0x01, 0x02}, {0x0C, 0x52, 0x52, 0x52, 0x3E},
            {0x7F, 0x08, 0x04, 0x04, 0x78}, {0x00, 0x44, 0x7D, 0x40, 0x00}, {0x20, 0x40, 0x44, 0x3D, 0x00}, {0x7F, 0x10, 0x28, 0x44, 0x00},
            {0x00, 0x41, 0x7F, 0x40, 0x00}, {0x7C, 0x04, 0x18, 0x04, 0x78}, {0x7C, 0x08, 0x04, 0x04, 0x78}, {0x38, 0x44, 0x44, 0x44, 0x38},
            {0x7C, 0x14, 0x14, 0x14, 0x08}, {0x08, 0x14, 0x14, 0x18, 0x7C}, {0x7C, 0x08, 0x04, 0x04, 0x08}, {0x48, 0x54, 0x54, 0x54, 0x20},
            {0x04, 0x3F, 0x44, 0x40, 0x20}, {0x3C, 0x40, 0x40, 0x20, 0x7C}, {0x1C, 0x20, 0x40, 0x20, 0x1C}, {0x3C, 0x40, 0x30, 0x40, 0x3C},
            {0x44, 0x28, 0x10, 0x28, 0x44}, {0x0C, 0x50, 0x50, 0x50, 0x3C}, {0x44, 0x64, 0x54, 0x4C, 0x44}, {0x00, 0x08, 0x36, 0x41, 0x00},
            {0x00, 0x00, 0x7F, 0x00, 0x00}, {0x00, 0x41, 0x36, 0x08, 0x00}, {0x08, 0x04, 0x08, 0x10, 0x08}, {0x3E, 0x22, 0x22, 0x22, 0x3E},
    };
}
