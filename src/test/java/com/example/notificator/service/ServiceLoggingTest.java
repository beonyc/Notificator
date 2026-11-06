package com.example.notificator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.example.notificator.model.Quote;
import com.example.notificator.repository.QuoteRepository;
import com.example.notificator.stats.StatsService;
import com.example.notificator.support.LogCapture;
import com.example.notificator.support.MutableClock;
import com.example.notificator.support.NotificationTestConfig;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Проверяет, что сервисы пишут в лог каждое действие бота над данными: регистрацию, добавление и удаление дел,
 * отметки, исправления за вчера, настройки напоминаний, выбор цитаты и просмотр статистики.
 * Часы тестовые: «сейчас» = 2026-11-07 08:00 по Москве.
 */
@DataJpaTest
@Import({NotificationTestConfig.class, TrackerService.class, ReminderSettingsService.class,
        QuoteService.class, StatsService.class})
class ServiceLoggingTest {

    private static final long CHAT = 100L;

    @Autowired
    private TrackerService tracker;
    @Autowired
    private ReminderSettingsService reminders;
    @Autowired
    private QuoteService quoteService;
    @Autowired
    private StatsService stats;
    @Autowired
    private QuoteRepository quotes;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        tracker.register(CHAT);
    }

    // ---------- TrackerService ----------

    @Test
    void registrationOfNewAndExistingUserIsLogged() {
        try (LogCapture log = new LogCapture(TrackerService.class)) {
            tracker.register(CHAT + 1);
            tracker.register(CHAT + 1);

            assertTrue(log.contains("Регистрация: создан новый пользователь id="), log.messages().toString());
            assertTrue(log.contains("Регистрация: пользователь уже зарегистрирован"), log.messages().toString());
        }
    }

    @Test
    void addedTaskIsLoggedWithIdAndTitle() {
        try (LogCapture log = new LogCapture(TrackerService.class)) {
            tracker.addTask(CHAT, "Читать 30 минут");

            String message = log.find("Добавлено дело").getFormattedMessage();
            assertTrue(message.contains("«Читать 30 минут»"), message);
            assertTrue(message.matches(".*id=\\d+.*"), message);
        }
    }

    @Test
    void markDoneResultsAreLoggedWithMatchingLevels() {
        tracker.addTask(CHAT, "Читать");
        long entryId = tracker.todayItems(CHAT).get(0).entryId();

        try (LogCapture log = new LogCapture(TrackerService.class)) {
            tracker.markDone(CHAT, entryId);
            tracker.markDone(CHAT, entryId);
            tracker.markDone(CHAT, 987654L);

            ILoggingEventAssert.assertLevel(log, "«Читать»: DONE", Level.INFO);
            ILoggingEventAssert.assertLevel(log, "ALREADY_DONE", Level.INFO);   // двойное нажатие — не тревога
            ILoggingEventAssert.assertLevel(log, "NOT_FOUND", Level.WARN);      // чужая или поддельная кнопка
        }
    }

    @Test
    void bulkOperationsAreLogged() {
        tracker.addTask(CHAT, "А");
        tracker.addTask(CHAT, "Б");

        try (LogCapture log = new LogCapture(TrackerService.class)) {
            tracker.markAllDone(CHAT);
            tracker.removeAllTasks(CHAT);

            assertTrue(log.contains("Отмечены все дела на сегодня: 2"), log.messages().toString());
            assertTrue(log.contains("Удалены все дела: 2"), log.messages().toString());
        }
    }

    @Test
    void singleRemovalIsLoggedAndMissingTaskIsWarning() {
        tracker.addTask(CHAT, "Читать");
        long taskId = tracker.activeTasks(CHAT).get(0).taskId();

        try (LogCapture log = new LogCapture(TrackerService.class)) {
            tracker.removeTask(CHAT, taskId);
            tracker.removeTask(CHAT, taskId); // повторно — уже удалено

            assertTrue(log.find("Удалено дело").getFormattedMessage().contains("«Читать»"));
            assertEquals(Level.WARN, log.find("Удаление дела").getLevel());
        }
    }

    @Test
    void yesterdayEditIsLogged() {
        clock.set("2026-11-06T09:00:00Z");
        tracker.addTask(CHAT, "Читать");
        long entryId = tracker.todayItems(CHAT).get(0).entryId();
        clock.set("2026-11-07T09:00:00Z");

        try (LogCapture log = new LogCapture(TrackerService.class)) {
            tracker.setYesterdayDone(CHAT, entryId, true);
            tracker.setYesterdayDone(CHAT, entryId, true);
            tracker.setYesterdayDone(CHAT, 987654L, true);

            assertEquals(Level.INFO, log.find("MARKED_DONE").getLevel());
            assertTrue(log.find("MARKED_DONE").getFormattedMessage().contains("нужно: выполнено"));
            assertEquals(Level.INFO, log.find("UNCHANGED").getLevel());
            assertEquals(Level.WARN, log.find("NOT_FOUND").getLevel());
        }
    }

    // ---------- ReminderSettingsService ----------

    @Test
    void reminderSettingChangesAreLogged() {
        try (LogCapture log = new LogCapture(ReminderSettingsService.class)) {
            reminders.setTimes(CHAT, List.of(LocalTime.of(20, 0), LocalTime.of(9, 30)));
            reminders.setEnabled(CHAT, false);
            reminders.setEnabled(CHAT, true);
            reminders.resetToDefault(CHAT);

            assertTrue(log.contains("заданы свои времена 09:30,20:00 (всего 2)"), log.messages().toString());
            assertTrue(log.contains("Напоминания: выключены"), log.messages().toString());
            assertTrue(log.contains("Напоминания: включены"), log.messages().toString());
            assertTrue(log.contains("возвращено стандартное время [13:00, 17:00, 21:00]"), log.messages().toString());
        }
    }

    // ---------- QuoteService ----------

    @Test
    void chosenQuoteIsLogged() {
        quotes.save(new Quote("Текст", "Сенека"));

        try (LogCapture log = new LogCapture(QuoteService.class)) {
            quoteService.pickQuote();

            String message = log.find("Цитата выбрана").getFormattedMessage();
            assertTrue(message.contains("(Сенека)"), message);
            assertTrue(message.contains("свежих цитат было 1 из 1"), message);
        }
    }

    @Test
    void reusingOldestQuoteIsMentionedInLog() {
        Quote used = new Quote("Текст", "Сенека");
        used.setLastUsedAt(clock.instant()); // только что показывали: свежих цитат нет
        quotes.save(used);

        try (LogCapture log = new LogCapture(QuoteService.class)) {
            quoteService.pickQuote();

            assertTrue(log.find("Цитата выбрана").getFormattedMessage().contains("взята самая давняя"));
        }
    }

    @Test
    void missingQuotesAreWarned() {
        try (LogCapture log = new LogCapture(QuoteService.class)) {
            quoteService.pickQuote();

            assertEquals(Level.WARN, log.find("нет ни одной цитаты").getLevel());
        }
    }

    // ---------- StatsService ----------

    @Test
    void weekStatisticsRequestIsLogged() {
        tracker.addTask(CHAT, "Читать");
        tracker.markAllDone(CHAT);

        try (LogCapture log = new LogCapture(StatsService.class)) {
            stats.currentWeek(CHAT);

            String message = log.find("Статистика недели").getFormattedMessage();
            assertTrue(message.contains("02.11".isEmpty() ? "" : "2026-11-02"), message);
            assertTrue(message.contains("выполнено 1 из 1 (100%)"), message);
        }
    }

    @Test
    void dayViewIsLoggedAndRefusedDayIsWarning() {
        try (LogCapture log = new LogCapture(StatsService.class)) {
            stats.dayReport(CHAT, LocalDate.of(2026, 11, 7));  // сегодня
            stats.dayReport(CHAT, LocalDate.of(2026, 11, 9));  // следующая неделя

            assertTrue(log.find("Просмотр дня 2026-11-07").getFormattedMessage().contains("сегодня"));
            assertEquals(Level.WARN, log.find("отклонён").getLevel());
            assertTrue(log.find("отклонён").getFormattedMessage().contains("OUT_OF_WEEK"));
            assertFalse(log.find("Просмотр дня 2026-11-07").getFormattedMessage().contains("отклонён"));
        }
    }

    /** Проверка уровня записи в логе. */
    private static final class ILoggingEventAssert {
        static void assertLevel(LogCapture log, String fragment, Level expected) {
            assertEquals(expected, log.find(fragment).getLevel(), "уровень записи с «" + fragment + "»");
        }
    }
}
