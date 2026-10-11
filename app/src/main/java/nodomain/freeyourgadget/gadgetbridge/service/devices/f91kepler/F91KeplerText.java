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

import androidx.annotation.Nullable;

import java.text.Normalizer;

/**
 * Folds phone text to what the watch's 5x7 font can draw, with the same table
 * as the firmware's own folding (FW91 f91_text.c, 3.1.14): German letters to
 * two letters (ä→ae, ß→ss), other Latin accents to the base letter, typographic
 * quotes, dashes and the ellipsis to ASCII, € to EUR, and invisible marks
 * (combining accents, zero-width characters, variation selectors, skin tones,
 * emoji tags) dropped.
 *
 * What has no look-alike (emoji, CJK, ...) depends on the firmware: from 3.1.14
 * the watch draws a box for it, so the code point is passed through unchanged;
 * older firmware has no box and draws stray pixels for any non-ASCII byte, so
 * it becomes a single '?'. A joined emoji (ZWJ sequence) or a flag (two
 * regional indicators) counts as one symbol either way.
 */
public final class F91KeplerText {
    private F91KeplerText() {
    }

    /** Marker in {@link #LATIN}: no look-alike (passed through or '?'). */
    private static final String BOX = "\u007F";

    /** U+00A0..U+017F, one entry per code point ("" = drop), as in f91_text.c s_latin. */
    private static final String[] LATIN = {
            // U+00A0 nbsp ! cent pound currency yen broken-bar section
            " ", "!", "c", "L", BOX, "Y", "|", "S",
            // U+00A8 diaeresis (c) ordf << not soft-hyphen (R) macron
            "\"", "c", "a", "\"", "-", "", "R", "-",
            // U+00B0 degree +- 2 3 acute micro pilcrow middle-dot
            "o", "+", "2", "3", "'", "u", "P", ".",
            // U+00B8 cedilla 1 ordm >> 1/4 1/2 3/4 inverted-?
            ",", "1", "o", "\"", BOX, BOX, BOX, "?",
            // U+00C0
            "A", "A", "A", "A", "Ae", "A", "AE", "C", "E", "E", "E", "E", "I", "I", "I", "I",
            // U+00D0
            "D", "N", "O", "O", "O", "O", "Oe", "x", "O", "U", "U", "U", "Ue", "Y", "Th", "ss",
            // U+00E0
            "a", "a", "a", "a", "ae", "a", "ae", "c", "e", "e", "e", "e", "i", "i", "i", "i",
            // U+00F0
            "d", "n", "o", "o", "o", "o", "oe", "/", "o", "u", "u", "u", "ue", "y", "th", "y",
            // U+0100 Latin Extended-A
            "A", "a", "A", "a", "A", "a", "C", "c", "C", "c", "C", "c", "C", "c", "D", "d",
            // U+0110
            "D", "d", "E", "e", "E", "e", "E", "e", "E", "e", "E", "e", "G", "g", "G", "g",
            // U+0120
            "G", "g", "G", "g", "H", "h", "H", "h", "I", "i", "I", "i", "I", "i", "I", "i",
            // U+0130
            "I", "i", "IJ", "ij", "J", "j", "K", "k", "k", "L", "l", "L", "l", "L", "l", "L",
            // U+0140
            "l", "L", "l", "N", "n", "N", "n", "N", "n", "n", "N", "n", "O", "o", "O", "o",
            // U+0150
            "O", "o", "OE", "oe", "R", "r", "R", "r", "R", "r", "S", "s", "S", "s", "S", "s",
            // U+0160
            "S", "s", "T", "t", "T", "t", "T", "t", "U", "u", "U", "u", "U", "u", "U", "u",
            // U+0170
            "U", "u", "U", "u", "W", "w", "Y", "y", "Y", "Z", "z", "Z", "z", "Z", "z", "s",
    };

    /**
     * @param text          what the phone wants to show
     * @param watchDrawsBox true from firmware 3.1.14 on: symbols without a
     *                      look-alike pass through for the watch to box
     */
    public static String fold(@Nullable final String raw, final boolean watchDrawsBox) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // Composed first, so "a" + combining diaeresis folds to "ae" like "ä".
        final String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
        final StringBuilder out = new StringBuilder(text.length());
        boolean skipNext = false;
        boolean flagHalf = false;
        int i = 0;
        while (i < text.length()) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);

            if (skipNext) {          // the emoji after a ZWJ is part of the same symbol
                skipNext = false;
                continue;
            }
            if (cp >= 0x1F1E6 && cp <= 0x1F1FF) { // a flag is two regional indicators
                flagHalf = !flagHalf;
                if (!flagHalf) {
                    if (watchDrawsBox) {
                        out.appendCodePoint(cp); // the watch boxes the pair as one
                    }
                    continue;
                }
            } else {
                flagHalf = false;
            }
            if (cp == 0x200D) {      // ZWJ: swallow the joined emoji
                if (watchDrawsBox) {
                    out.appendCodePoint(cp); // and let the watch do the same
                    if (i < text.length()) {
                        final int next = text.codePointAt(i);
                        out.appendCodePoint(next);
                        i += Character.charCount(next);
                    }
                } else {
                    skipNext = true;
                }
                continue;
            }
            final String m = map(cp);
            if (BOX.equals(m)) {
                if (watchDrawsBox) {
                    out.appendCodePoint(cp);
                } else {
                    out.append('?');
                }
            } else {
                out.append(m);
            }
        }
        return out.toString();
    }

    /** One code point: its ASCII spelling, "" to drop it, or {@link #BOX}. */
    private static String map(final int cp) {
        if (cp < 0x20 || cp == 0x7F) {
            return " ";
        }
        if (cp < 0x7F) {
            return String.valueOf((char) cp);
        }
        if (cp < 0xA0) {
            return "";
        }
        if (cp < 0x180) {
            return LATIN[cp - 0xA0];
        }
        if ((cp >= 0x300 && cp <= 0x36F)
                || (cp >= 0x200B && cp <= 0x200F)
                || cp == 0x2060 || cp == 0xFEFF || cp == 0x20E3
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0x1F3FB && cp <= 0x1F3FF)
                || (cp >= 0xE0020 && cp <= 0xE007F)) {
            return "";
        }
        if (cp >= 0x2010 && cp <= 0x2015) {
            return "-";
        }
        if (cp >= 0x2018 && cp <= 0x201B) {
            return "'";
        }
        if (cp >= 0x201C && cp <= 0x201F) {
            return "\"";
        }
        switch (cp) {
            case 0x2022: return "*";
            case 0x2039: return "<";
            case 0x203A: return ">";
            case 0x2026: return "...";
            case 0x20AC: return "EUR";
            default: return BOX;
        }
    }
}
