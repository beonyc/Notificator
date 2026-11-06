package com.example.notificator.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import com.example.notificator.support.MutableClock;
import com.example.notificator.support.NotificationTestConfig;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Тесты загрузки данных и расчёта недели на БД в памяти.
 * 2026-11-07 — суббота; Москва = UTC+3.
 */
@DataJpaTest
@Import({NotificationTestConfig.class, TrackerService.class, StatsService.class})
class StatsServiceTest {

    private static final long CHAT = 100L;

    @Autowired
    private StatsService stats;
    @Autowired
    private TrackerService tracker;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        tracker.register(CHAT);
    }

    @Test
    void currentWeekCoversMondayUntilToday() {
        clock.set("2026-11-03T09:00:00Z"); // вторник, полдень по Москве
        tracker.addTask(CHAT, "Читать");
        tracker.markAllDone(CHAT);
        clock.set("2026-11-05T09:00:00Z"); // четверг
        tracker.addTask(CHAT, "Зарядка");
        clock.set("2026-11-07T09:00:00Z"); // суббота

        WeeklyStats s = stats.currentWeek(CHAT);

        assertEquals(LocalDate.of(2026, 11, 2), s.from());
        assertEquals(LocalDate.of(2026, 11, 7), s.to());
        assertTrue(s.hasData());
        assertEquals(2, s.days().size());
        assertEquals(1, s.tasks().get(0).daysDone());
        assertEquals("Читать", s.tasks().get(0).title());
    }

    @Test
    void currentWeekWithoutTasksHasNoData() {
        clock.set("2026-11-07T09:00:00Z");

        assertFalse(stats.currentWeek(CHAT).hasData());
    }

    @Test
    void weekStartsOnMondayInUsersTimezone() {
        // 21:30 UTC в воскресенье 8 ноября = 00:30 в понедельник 9 ноября по Москве
        clock.set("2026-11-08T21:30:00Z");

        WeeklyStats s = stats.currentWeek(CHAT);

        assertEquals(LocalDate.of(2026, 11, 9), s.from());
        assertEquals(LocalDate.of(2026, 11, 9), s.to());
    }

    @Test
    void currentWeekIncludesComparisonWithSameDaysOfPreviousWeek() {
        clock.set("2026-10-27T09:00:00Z"); // вторник прошлой недели: 1 дело, не выполнено
        tracker.addTask(CHAT, "Читать");
        clock.set("2026-11-03T09:00:00Z"); // вторник этой недели: дело выполнено
        tracker.markAllDone(CHAT);
        clock.set("2026-11-04T09:00:00Z"); // среда

        WeeklyStats s = stats.currentWeek(CHAT);

        assertEquals(0, s.previousPercent());
        assertEquals(100, s.percent());
    }

    /**
     * Готовит неделю: вт 3.11 — дела А и Б, А выполнено; чт 5.11 — оба дела без отметки о выполнении;
     * сегодня — пятница 6.11.
     */
    private void prepareWeekUntilFriday() {
        clock.set("2026-11-03T09:00:00Z");
        tracker.addTask(CHAT, "А");
        tracker.addTask(CHAT, "Б");
        tracker.markDone(CHAT, tracker.todayItems(CHAT).get(0).entryId());
        clock.set("2026-11-05T09:00:00Z");
        tracker.todayItems(CHAT); // создаёт отметки на четверг
        clock.set("2026-11-06T09:00:00Z");
    }

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    @Test
    void weekOverviewListsEveryDayFromMondayUntilToday() {
        prepareWeekUntilFriday();

        WeekOverview w = stats.weekOverview(CHAT);

        assertEquals(d(2), w.monday());
        assertEquals(d(6), w.today());
        assertEquals(d(8), w.sunday());
        assertEquals(List.of(
                new DayStat(d(2), 0, 0),   // пн — дел не было
                new DayStat(d(3), 2, 1),   // вт
                new DayStat(d(4), 0, 0),   // ср — бот не создавал отметок
                new DayStat(d(5), 2, 0),   // чт
                new DayStat(d(6), 2, 0)),  // пт (сегодня): отметки созданы при обзоре
                w.days());
    }

    @Test
    void weekOverviewOnMondayHasOnlyToday() {
        clock.set("2026-11-08T21:30:00Z"); // понедельник 9.11, 00:30 по Москве

        WeekOverview w = stats.weekOverview(CHAT);

        assertEquals(d(9), w.monday());
        assertEquals(List.of(new DayStat(d(9), 0, 0)), w.days());
    }

    @Test
    void dayReportOfPastDayShowsStoredStatuses() {
        prepareWeekUntilFriday();

        DayReport r = stats.dayReport(CHAT, d(3));

        assertEquals(DayReport.Status.OK, r.status());
        assertFalse(r.today());
        assertEquals(List.of("А", "Б"), r.items().stream().map(TodayItem::title).toList());
        assertEquals(List.of(true, false), r.items().stream().map(TodayItem::done).toList());
    }

    @Test
    void dayReportOfDayWithoutEntriesIsEmptyButOk() {
        prepareWeekUntilFriday();

        DayReport r = stats.dayReport(CHAT, d(4));

        assertEquals(DayReport.Status.OK, r.status());
        assertTrue(r.items().isEmpty());
    }

    @Test
    void dayReportOfTodayMatchesTodayListAndIsMarkedAsToday() {
        prepareWeekUntilFriday();

        DayReport r = stats.dayReport(CHAT, d(6));

        assertEquals(DayReport.Status.OK, r.status());
        assertTrue(r.today());
        assertEquals(2, r.items().size());
    }

    @Test
    void dayReportRefusesFutureDaysAndDaysOutsideCurrentWeek() {
        prepareWeekUntilFriday();

        assertEquals(DayReport.Status.FUTURE, stats.dayReport(CHAT, d(7)).status());   // суббота этой недели
        assertEquals(DayReport.Status.FUTURE, stats.dayReport(CHAT, d(8)).status());   // воскресенье
        assertEquals(DayReport.Status.OUT_OF_WEEK, stats.dayReport(CHAT, d(1)).status());  // вс прошлой недели
        assertEquals(DayReport.Status.OUT_OF_WEEK, stats.dayReport(CHAT, d(9)).status());  // пн следующей
        assertEquals(DayReport.Status.OUT_OF_WEEK, stats.dayReport(CHAT, LocalDate.of(2025, 11, 4)).status());
        assertTrue(stats.dayReport(CHAT, d(7)).items().isEmpty());
    }

    @Test
    void removedTaskStaysInPastDayHistoryButNotInToday() {
        prepareWeekUntilFriday();
        long taskA = tracker.activeTasks(CHAT).get(0).taskId();
        tracker.removeTask(CHAT, taskA);

        assertEquals(List.of("А", "Б"), stats.dayReport(CHAT, d(3)).items().stream().map(TodayItem::title).toList());
        assertEquals(List.of("Б"), stats.dayReport(CHAT, d(6)).items().stream().map(TodayItem::title).toList());
    }

    @Test
    void dayReportAndOverviewRequireRegisteredUser() {
        clock.set("2026-11-06T09:00:00Z");

        assertThrows(UserNotRegisteredException.class, () -> stats.weekOverview(999L));
        assertThrows(UserNotRegisteredException.class, () -> stats.dayReport(999L, d(3)));
    }

    @Test
    void mondayOfReturnsMondayOfTheSameWeek() {
        assertEquals(LocalDate.of(2026, 11, 2), StatsService.mondayOf(LocalDate.of(2026, 11, 2))); // пн
        assertEquals(LocalDate.of(2026, 11, 2), StatsService.mondayOf(LocalDate.of(2026, 11, 4))); // ср
        assertEquals(LocalDate.of(2026, 11, 2), StatsService.mondayOf(LocalDate.of(2026, 11, 8))); // вс
        assertEquals(LocalDate.of(2026, 11, 9), StatsService.mondayOf(LocalDate.of(2026, 11, 9))); // следующий пн
    }

    @Test
    void unregisteredUserCannotGetStatistics() {
        assertThrows(UserNotRegisteredException.class, () -> stats.currentWeek(999L));
    }
}
