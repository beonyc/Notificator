package com.example.notificator.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Тесты текста отчёта. */
class ReportFormatterTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate SUN = LocalDate.of(2026, 11, 8);

    private static StatEntry e(LocalDate date, long taskId, String title, boolean done) {
        return new StatEntry(date, taskId, title, done);
    }

    @Test
    void formatsFullFinalReport() {
        List<StatEntry> list = new ArrayList<>();
        list.add(e(MON, 1, "Читать", true));
        list.add(e(MON, 2, "Писать", true));
        list.add(e(MON.plusDays(1), 1, "Читать", true));
        list.add(e(MON.plusDays(1), 2, "Писать", false));
        list.add(e(MON.plusDays(2), 1, "Читать", true));
        list.add(e(MON.plusDays(2), 2, "Писать", true));
        list.add(e(MON.plusDays(3), 1, "Читать", false));
        list.add(e(MON.plusDays(3), 2, "Писать", false));

        String text = ReportFormatter.format(WeeklyStatsCalculator.calculate(list, MON, SUN, false), true);

        assertEquals("""
                📊 <b>Итоги недели</b> 02.11–08.11

                ▰▰▰▰▰▰▱▱▱▱ 63%
                Выполнено <b>5</b> из <b>8</b>

                🏆 Оценка: <b>59/100</b> — 😐 Средне
                📈 С прошлой неделей сравнить нельзя: там нет данных.

                🥇 Лучший день: понедельник (100%)
                🥶 Худший день: четверг (0%)

                📝 <b>По делам:</b>
                • Читать — 3 из 4
                • Писать — 2 из 4""", text);
    }

    @Test
    void taskTitlesAreHtmlEscaped() {
        List<StatEntry> list = List.of(e(MON, 1, "Сдать <отчёт> & Ко", true));

        String text = ReportFormatter.format(WeeklyStatsCalculator.calculate(list, MON, SUN, false), true);

        assertTrue(text.contains("• Сдать &lt;отчёт&gt; &amp; Ко — 1 из 1"), text);
        assertFalse(text.contains("<отчёт>"), text);
    }

    @Test
    void verdictGetsMatchingEmoji() {
        String fire = ReportFormatter.format(WeeklyStatsCalculator.calculate(
                List.of(e(MON, 1, "Д", true)), MON, SUN, false), true);
        String lazy = ReportFormatter.format(WeeklyStatsCalculator.calculate(
                List.of(e(MON, 1, "Д", false)), MON, SUN, false), true);

        assertTrue(fire.contains("— 🔥 Огонь"), fire);
        assertTrue(lazy.contains("— 😴 Лень победила"), lazy);
    }

    @Test
    void showsStreakWithCorrectPlural() {
        for (int days = 1; days <= 5; days++) {
            List<StatEntry> list = new ArrayList<>();
            for (int i = 0; i < days; i++) {
                list.add(e(MON.plusDays(i), 1, "Д", true));
            }
            String text = ReportFormatter.format(
                    WeeklyStatsCalculator.calculate(list, MON, MON.plusDays(days - 1), false), true);

            String word = days == 1 ? "день" : days < 5 ? "дня" : "дней";
            assertTrue(text.contains("Серия 100%: " + days + " " + word + " подряд"), text);
        }
    }

    @Test
    void hidesStreakLineWhenNoStreak() {
        List<StatEntry> list = List.of(e(MON, 1, "Д", false));

        String text = ReportFormatter.format(WeeklyStatsCalculator.calculate(list, MON, SUN, false), true);

        assertFalse(text.contains("Серия"), text);
    }

    @Test
    void showsSingleDayLabelWhenBestEqualsWorst() {
        List<StatEntry> list = List.of(e(MON, 1, "Д", true));

        String text = ReportFormatter.format(WeeklyStatsCalculator.calculate(list, MON, SUN, false), true);

        assertTrue(text.contains("День: понедельник (100%)"), text);
        assertFalse(text.contains("Лучший день"), text);
    }

    @Test
    void comparisonShowsArrowUpDownOrNoChange() {
        LocalDate prevMon = MON.minusDays(7);

        assertTrue(compareText(prevMon, false, true).contains("С прошлой неделей: ↑ 100% (было 0%)"));
        assertTrue(compareText(prevMon, true, false).contains("С прошлой неделей: ↓ 100% (было 100%)"));
        assertTrue(compareText(prevMon, true, true).contains("С прошлой неделей: без изменений (было 100%)"));
    }

    private static String compareText(LocalDate prevMon, boolean prevDone, boolean nowDone) {
        List<StatEntry> list = List.of(e(prevMon, 1, "Д", prevDone), e(MON, 1, "Д", nowDone));
        return ReportFormatter.format(WeeklyStatsCalculator.calculate(list, MON, SUN, false), true);
    }

    @Test
    void inProgressReportHasDifferentTitle() {
        List<StatEntry> list = List.of(e(MON, 1, "Д", true));

        String text = ReportFormatter.format(
                WeeklyStatsCalculator.calculate(list, MON, MON.plusDays(2), true), false);

        assertTrue(text.startsWith("📊 <b>Неделя в процессе</b> 02.11–04.11"), text);
    }

    @Test
    void emptyPeriodSuggestsAddingTasks() {
        String text = ReportFormatter.format(WeeklyStatsCalculator.calculate(List.of(), MON, SUN, false), true);

        assertEquals("📊 <b>Итоги недели</b> 02.11–08.11\n\n📭 За этот период дел не было.\n"
                + "Добавь: /add Читать 30 минут", text);
    }
}
