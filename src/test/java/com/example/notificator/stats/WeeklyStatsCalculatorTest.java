package com.example.notificator.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Тесты формул недельной статистики. Неделя в примерах: понедельник 2 ноября – воскресенье 8 ноября 2026.
 * Калькулятор — чистая функция, поэтому БД и Spring не нужны.
 */
class WeeklyStatsCalculatorTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate SUN = LocalDate.of(2026, 11, 8);

    private static StatEntry e(LocalDate date, long taskId, String title, boolean done) {
        return new StatEntry(date, taskId, title, done);
    }

    /** Четыре дня: пн 2/2, вт 1/2, ср 2/2, чт 0/2 — всего 5 из 8. */
    private static List<StatEntry> sampleWeek() {
        List<StatEntry> list = new ArrayList<>();
        list.add(e(MON, 1, "Читать", true));
        list.add(e(MON, 2, "Писать", true));
        list.add(e(MON.plusDays(1), 1, "Читать", true));
        list.add(e(MON.plusDays(1), 2, "Писать", false));
        list.add(e(MON.plusDays(2), 1, "Читать", true));
        list.add(e(MON.plusDays(2), 2, "Писать", true));
        list.add(e(MON.plusDays(3), 1, "Читать", false));
        list.add(e(MON.plusDays(3), 2, "Писать", false));
        return list;
    }

    private static List<StatEntry> fullDays(LocalDate from, int days, long taskId, boolean done) {
        List<StatEntry> list = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            list.add(e(from.plusDays(i), taskId, "Дело", done));
        }
        return list;
    }

    @Test
    void completionPercentIsRoundedDoneOverTotal() {
        WeeklyStats s = WeeklyStatsCalculator.calculate(sampleWeek(), MON, SUN, false);

        assertEquals(8, s.total());
        assertEquals(5, s.done());
        assertEquals(63, s.percent()); // 62.5 округляется до 63
    }

    @Test
    void scoreIs70PercentCompletionPlus30PercentStability() {
        WeeklyStats s = WeeklyStatsCalculator.calculate(sampleWeek(), MON, SUN, false);

        // стабильность: дней с >=80% — 2 из 4 = 50%; score = 0.7*63 + 0.3*50 = 59.1
        assertEquals(59, s.score());
        assertEquals("Средне", s.verdict());
    }

    @Test
    void scoreFormulaBoundaries() {
        assertEquals(100, WeeklyStatsCalculator.score(100, 100));
        assertEquals(0, WeeklyStatsCalculator.score(0, 0));
        assertEquals(86, WeeklyStatsCalculator.score(80, 100)); // 56 + 30
        assertEquals(70, WeeklyStatsCalculator.score(100, 0));  // стабильность 0 — максимум 70
        assertEquals(30, WeeklyStatsCalculator.score(0, 100));
    }

    @Test
    void verdictThresholds() {
        assertEquals("Огонь", WeeklyStatsCalculator.verdict(100));
        assertEquals("Огонь", WeeklyStatsCalculator.verdict(90));
        assertEquals("Хорошо", WeeklyStatsCalculator.verdict(89));
        assertEquals("Хорошо", WeeklyStatsCalculator.verdict(70));
        assertEquals("Средне", WeeklyStatsCalculator.verdict(69));
        assertEquals("Средне", WeeklyStatsCalculator.verdict(50));
        assertEquals("Проленился", WeeklyStatsCalculator.verdict(49));
        assertEquals("Проленился", WeeklyStatsCalculator.verdict(0));
    }

    @Test
    void stabilityCountsOnlyDaysWithData() {
        // 80% — граница «хорошего» дня: 4 из 5 выполнено
        List<StatEntry> list = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            list.add(e(MON, i, "Дело " + i, i != 5));
        }

        WeeklyStats s = WeeklyStatsCalculator.calculate(list, MON, SUN, false);

        assertEquals(80, s.percent());
        assertEquals(86, s.score()); // 0.7*80 + 0.3*100 (один день, он «хороший»)
    }

    @Test
    void bestAndWorstDayPickEarliestOnTies() {
        WeeklyStats s = WeeklyStatsCalculator.calculate(sampleWeek(), MON, SUN, false);

        assertEquals(MON, s.best().date());      // 100% и в пн, и в ср — берём более ранний
        assertEquals(100, s.best().percent());
        assertEquals(MON.plusDays(3), s.worst().date());
        assertEquals(0, s.worst().percent());
    }

    @Test
    void worstDayTieTakesEarliest() {
        List<StatEntry> list = new ArrayList<>();
        list.add(e(MON, 1, "Д", true));
        list.add(e(MON, 2, "Д2", false));
        list.add(e(MON.plusDays(1), 1, "Д", true));
        list.add(e(MON.plusDays(1), 2, "Д2", false));

        WeeklyStats s = WeeklyStatsCalculator.calculate(list, MON, SUN, false);

        assertEquals(MON, s.worst().date());
        assertEquals(MON, s.best().date());
    }

    @Test
    void singleDayIsBothBestAndWorst() {
        WeeklyStats s = WeeklyStatsCalculator.calculate(fullDays(MON, 1, 1, true), MON, SUN, false);

        assertEquals(s.best(), s.worst());
    }

    @Test
    void perTaskStatsShowDoneDaysOutOfDaysOnList() {
        WeeklyStats s = WeeklyStatsCalculator.calculate(sampleWeek(), MON, SUN, false);

        assertEquals(List.of(new TaskStat("Читать", 3, 4), new TaskStat("Писать", 2, 4)), s.tasks());
    }

    @Test
    void taskAddedMidWeekIsCountedOnlyForDaysItWasOnTheList() {
        List<StatEntry> list = fullDays(MON, 7, 1, true);
        list.add(e(MON.plusDays(4), 2, "Новое", true));
        list.add(e(MON.plusDays(5), 2, "Новое", false));

        WeeklyStats s = WeeklyStatsCalculator.calculate(list, MON, SUN, false);

        assertEquals(new TaskStat("Новое", 1, 2), s.tasks().get(1));
        assertEquals(new TaskStat("Дело", 7, 7), s.tasks().get(0));
    }

    @Test
    void entriesOutsidePeriodAreIgnored() {
        List<StatEntry> list = sampleWeek();
        list.add(e(MON.minusDays(1), 1, "Читать", false)); // воскресенье прошлой недели
        list.add(e(SUN.plusDays(1), 1, "Читать", false));  // понедельник следующей

        WeeklyStats s = WeeklyStatsCalculator.calculate(list, MON, SUN, false);

        assertEquals(8, s.total());
    }

    @Test
    void perfectWeekGivesFullScoreAndSevenDayStreak() {
        WeeklyStats s = WeeklyStatsCalculator.calculate(fullDays(MON, 7, 1, true), MON, SUN, false);

        assertEquals(100, s.percent());
        assertEquals(100, s.score());
        assertEquals("Огонь", s.verdict());
        assertEquals(7, s.streak());
    }

    @Test
    void weekWithoutCompletedTasksIsLazy() {
        WeeklyStats s = WeeklyStatsCalculator.calculate(fullDays(MON, 7, 1, false), MON, SUN, false);

        assertEquals(0, s.percent());
        assertEquals(0, s.score());
        assertEquals("Проленился", s.verdict());
        assertEquals(0, s.streak());
    }

    @Test
    void noDataGivesEmptyStats() {
        WeeklyStats s = WeeklyStatsCalculator.calculate(List.of(), MON, SUN, false);

        assertFalse(s.hasData());
        assertEquals(0, s.percent());
        assertEquals(0, s.score());
        assertNull(s.best());
        assertNull(s.worst());
        assertNull(s.previousPercent());
        assertTrue(s.tasks().isEmpty());
    }

    // ---------- Серия ----------

    @Test
    void streakStopsAtFirstIncompleteDayGoingBackwards() {
        List<StatEntry> list = new ArrayList<>();
        list.add(e(MON, 1, "Д", true));
        list.add(e(MON.plusDays(1), 1, "Д", false)); // провал
        list.add(e(MON.plusDays(2), 1, "Д", true));
        list.add(e(MON.plusDays(3), 1, "Д", true));

        assertEquals(2, WeeklyStatsCalculator.calculate(list, MON, MON.plusDays(3), false).streak());
    }

    @Test
    void dayWithoutDataBreaksStreak() {
        List<StatEntry> list = new ArrayList<>();
        list.add(e(MON, 1, "Д", true));
        list.add(e(MON.plusDays(2), 1, "Д", true)); // вторник без данных

        assertEquals(1, WeeklyStatsCalculator.calculate(list, MON, MON.plusDays(2), false).streak());
    }

    @Test
    void streakContinuesAcrossWeekBoundary() {
        List<StatEntry> list = new ArrayList<>();
        list.add(e(MON.minusDays(2), 1, "Д", true)); // сб прошлой недели
        list.add(e(MON.minusDays(1), 1, "Д", true)); // вс прошлой недели
        list.add(e(MON, 1, "Д", true));

        assertEquals(3, WeeklyStatsCalculator.calculate(list, MON, MON, false).streak());
    }

    @Test
    void unfinishedTodayDoesNotBreakStreakButIsNotCounted() {
        List<StatEntry> list = new ArrayList<>();
        list.add(e(MON, 1, "Д", true));
        list.add(e(MON.plusDays(1), 1, "Д", true));
        list.add(e(MON.plusDays(2), 1, "Д", false)); // сегодня: ещё не всё сделано

        assertEquals(2, WeeklyStatsCalculator.calculate(list, MON, MON.plusDays(2), true).streak());
        assertEquals(0, WeeklyStatsCalculator.calculate(list, MON, MON.plusDays(2), false).streak());
    }

    @Test
    void completedTodayIsCountedInStreakEvenInProgress() {
        List<StatEntry> list = fullDays(MON, 3, 1, true);

        assertEquals(3, WeeklyStatsCalculator.calculate(list, MON, MON.plusDays(2), true).streak());
    }

    @Test
    void todayWithoutEntriesYetDoesNotBreakStreakInProgress() {
        List<StatEntry> list = fullDays(MON, 2, 1, true); // пн, вт; среда ещё без отметок

        assertEquals(2, WeeklyStatsCalculator.calculate(list, MON, MON.plusDays(2), true).streak());
    }

    // ---------- Сравнение с прошлой неделей ----------

    @Test
    void previousWeekPercentIsTakenFromSamePeriodOneWeekEarlier() {
        List<StatEntry> list = new ArrayList<>();
        LocalDate prevTue = MON.minusDays(6); // вторник 27 октября
        list.add(e(prevTue, 1, "Д", true));
        list.add(e(prevTue, 2, "Д2", false)); // прошлая неделя: 50%
        list.addAll(fullDays(MON, 2, 1, true)); // эта неделя: 100%

        WeeklyStats s = WeeklyStatsCalculator.calculate(list, MON, SUN, false);

        assertEquals(50, s.previousPercent());
        assertEquals(100, s.percent());
    }

    @Test
    void partialWeekIsComparedWithSameDaysOfPreviousWeek() {
        List<StatEntry> list = new ArrayList<>();
        list.add(e(MON.minusDays(7), 1, "Д", true));   // пн прошлой недели: 100%
        list.add(e(MON.minusDays(3), 1, "Д", false));  // чт прошлой недели: не входит в сравнение
        list.add(e(MON, 1, "Д", true));                // пн этой недели

        WeeklyStats s = WeeklyStatsCalculator.calculate(list, MON, MON.plusDays(1), true);

        assertEquals(100, s.previousPercent()); // чт прошлой недели не учтён
    }

    @Test
    void previousPercentIsNullWithoutPreviousData() {
        assertNull(WeeklyStatsCalculator.calculate(sampleWeek(), MON, SUN, false).previousPercent());
    }
}
