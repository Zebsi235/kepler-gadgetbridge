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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins the phone-side folding to the firmware's table (FW91 f91_text.c,
 * 3.1.14) and to the old-firmware fallback ('?' instead of the watch's box).
 */
public class F91KeplerTextTest {
    private static String old(final String s) {
        return F91KeplerText.fold(s, false);
    }

    private static String box(final String s) {
        return F91KeplerText.fold(s, true);
    }

    @Test
    public void asciiIsUnchanged() {
        assertEquals("Anna: see you at 7!", old("Anna: see you at 7!"));
        assertEquals("Anna: see you at 7!", box("Anna: see you at 7!"));
    }

    @Test
    public void germanLettersAreSpelledWithTwoLetters() {
        assertEquals("Muesli Strasse Aepfel Oel", old("Müsli Straße Äpfel Öl"));
        assertEquals("Ueber aerger", old("Über ärger"));
    }

    @Test
    public void otherAccentsBecomeTheBaseLetter() {
        assertEquals("Cafe creme a la Francois, Lodz, Ceske", old("Café crème à la François, Łódź, České"));
        assertEquals("Hakon Oystein", old("Håkon Øystein"));
    }

    @Test
    public void lettersBeyondTheTableKeepTheirBaseLetter() {
        assertEquals("Nguyen", old("Nguyễn"));
        assertEquals("Iasi Constanta", old("Iași Constanța"));
        assertEquals("Nguyen", box("Nguyễn"));
        assertEquals("Bold AB", old("\uD835\uDC01\uD835\uDC28\uD835\uDC25\uD835\uDC1D \uFF21\uFF22")); // 𝐁𝐨𝐥𝐝 ＡＢ
        assertEquals("fish", old("\uFB01sh")); // ﬁ ligature
    }

    @Test
    public void decomposedAccentsFoldLikeComposedOnes() {
        assertEquals("ae", old("ä"));
        assertEquals("e", old("é"));
    }

    @Test
    public void typographyBecomesAscii() {
        assertEquals("\"Hi\" - it's 5 EUR...", old("“Hi” – it’s 5 €…"));
        assertEquals("* <a> 2 o", old("• ‹a› ² °"));
    }

    @Test
    public void invisibleMarksAreDropped() {
        assertEquals("ab", old("a​b"));
        assertEquals("ab", old("a­b"));
        assertEquals("ab", old("﻿a⁠b"));
    }

    @Test
    public void controlsBecomeSpaces() {
        assertEquals("line one line two", old("line one\nline two"));
    }

    @Test
    public void emojiBecomeOneQuestionMarkOnOldFirmware() {
        assertEquals("ok ?", old("ok 👍"));              // 👍
        assertEquals("ok ?", old("ok 👍🏽"));  // 👍🏽 (skin tone)
        assertEquals("? !", old("❤️ !"));                 // ❤️ (variation selector)
        assertEquals("?", old("👩‍💻"));   // 👩‍💻 (ZWJ)
        assertEquals("? ?", old("🇦🇹 🇩🇪")); // 🇦🇹 🇩🇪
        assertEquals("?", old("中"));                           // CJK
    }

    @Test
    public void emojiPassThroughForTheWatchToBoxFrom3114() {
        assertEquals("ok 👍", box("ok 👍"));
        assertEquals("ok 👍", box("ok 👍🏽"));
        assertEquals("👩‍💻", box("👩‍💻"));
        assertEquals("🇦🇹", box("🇦🇹"));
        // Folding still happens on the phone, so the watch's buffer holds more text.
        assertEquals("Muesli 🥣", box("Müsli 🥣"));
    }

    @Test
    public void nullAndEmpty() {
        assertEquals("", old(null));
        assertEquals("", box(""));
    }
}
