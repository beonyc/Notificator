package com.example.notificator.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Тесты вспомогательных классов оформления сообщений. */
class HtmlAndVisualsTest {

    @Test
    void escapeReplacesCharactersSignificantForHtml() {
        assertEquals("Сдать &lt;отчёт&gt; &amp; Ко", Html.escape("Сдать <отчёт> & Ко"));
        assertEquals("&lt;b&gt;жирный&lt;/b&gt;", Html.escape("<b>жирный</b>"));
    }

    @Test
    void escapeDoesNotDoubleEscapeAmpersandInReplacements() {
        // «&» заменяется первым, иначе «<» дало бы «&amp;lt;»
        assertEquals("&lt;", Html.escape("<"));
        assertEquals("&amp;lt;", Html.escape("&lt;"));
    }

    @Test
    void escapeLeavesOrdinaryTextAndEmojiUntouched() {
        assertEquals("Читать 30 минут ✅ «ёлка» 5 > 3".replace(">", "&gt;"), Html.escape("Читать 30 минут ✅ «ёлка» 5 > 3"));
        assertEquals("", Html.escape(""));
    }

    @Test
    void barShowsOneSegmentPerTenPercent() {
        assertEquals("▱▱▱▱▱▱▱▱▱▱", Visuals.bar(0));
        assertEquals("▰▰▰▰▰▱▱▱▱▱", Visuals.bar(50));
        assertEquals("▰▰▰▰▰▰▱▱▱▱", Visuals.bar(63));
        assertEquals("▰▰▰▰▰▰▰▱▱▱", Visuals.bar(67));
        assertEquals("▰▰▰▰▰▰▰▰▰▰", Visuals.bar(100));
    }

    @Test
    void barClampsOutOfRangeValues() {
        assertEquals("▱▱▱▱▱▱▱▱▱▱", Visuals.bar(-20));
        assertEquals("▰▰▰▰▰▰▰▰▰▰", Visuals.bar(250));
    }

    @Test
    void barAlwaysHasTenSegments() {
        for (int p = 0; p <= 100; p++) {
            assertEquals(10, Visuals.bar(p).length(), "процент " + p);
        }
    }

    @Test
    void percentIsRoundedAndSafeForZeroTotal() {
        assertEquals(0, Visuals.percent(0, 0));
        assertEquals(67, Visuals.percent(2, 3));
        assertEquals(63, Visuals.percent(5, 8)); // 62.5 округляется вверх
        assertEquals(100, Visuals.percent(4, 4));
    }

    @Test
    void verdictEmojiMatchesVerdict() {
        assertEquals("🔥", Visuals.verdictEmoji("Огонь"));
        assertEquals("👍", Visuals.verdictEmoji("Хорошо"));
        assertEquals("😐", Visuals.verdictEmoji("Средне"));
        assertEquals("😴", Visuals.verdictEmoji("Проленился"));
    }
}
