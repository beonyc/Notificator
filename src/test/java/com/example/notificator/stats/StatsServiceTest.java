package com.example.notificator.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import com.example.notificator.support.MutableClock;
import com.example.notificator.support.NotificationTestConfig;
import java.time.LocalDate;
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
