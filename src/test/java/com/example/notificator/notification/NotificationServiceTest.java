package com.example.notificator.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.notificator.model.Quote;
import com.example.notificator.model.User;
import com.example.notificator.repository.QuoteRepository;
import com.example.notificator.repository.UserRepository;
import com.example.notificator.service.QuoteService;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.support.MutableClock;
import com.example.notificator.support.NotificationTestConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Тесты расписания уведомлений на настоящей БД (в памяти) с управляемыми часами.
 * Даты: 7 ноября 2026, Москва = UTC+3 (08:00 МСК = 05:00 UTC, 13:00 = 10:00, 17:00 = 14:00).
 */
@DataJpaTest
@Import({NotificationTestConfig.class, TrackerService.class, QuoteService.class, NotificationService.class})
class NotificationServiceTest {

    private static final long CHAT = 100L;

    // Моменты в UTC
    private static final String YESTERDAY_NOON = "2026-11-06T09:00:00Z";
    private static final String MSK_0759 = "2026-11-07T04:59:00Z";
    private static final String MSK_0800 = "2026-11-07T05:00:00Z";
    private static final String MSK_0820 = "2026-11-07T05:20:00Z";
    private static final String MSK_0831 = "2026-11-07T05:31:00Z";
    private static final String MSK_1200 = "2026-11-07T09:00:00Z";
    private static final String MSK_1300 = "2026-11-07T10:00:00Z";
    private static final String MSK_1340 = "2026-11-07T10:40:00Z";
    private static final String MSK_1700 = "2026-11-07T14:00:00Z";

    @Autowired
    private NotificationService service;
    @Autowired
    private TrackerService tracker;
    @Autowired
    private UserRepository users;
    @Autowired
    private QuoteRepository quotes;
    @Autowired
    private MutableClock clock;

    private long userId;

    @BeforeEach
    void setUp() {
        tracker.register(CHAT);
        userId = users.findByTelegramChatId(CHAT).orElseThrow().getId();
    }

    // ---------- Утренний итог ----------

    @Test
    void summaryAtEightReportsYesterdayAndCreatesTodayEntries() {
        tasksDoneYesterday(2, 1); // вчера: 2 дела, выполнено 1
        clock.set(MSK_0800);

        PendingNotification n = service.prepare(userId).orElseThrow();

        assertEquals(NotificationKind.SUMMARY, n.kind());
        assertEquals(CHAT, n.chatId());
        assertEquals(LocalDateTime.of(2026, 11, 7, 8, 0), n.slot());
        assertTrue(n.text().startsWith("Вчера выполнено 1 из 2 (50%)."), n.text());
        assertTrue(n.text().contains("Не доделано: 1. Сегодня без отговорок."), n.text());
        assertTrue(n.text().contains("Сегодня в списке: 2 дела."), n.text());
        assertEquals(2, tracker.todayItems(CHAT).size()); // отметки на новый день созданы
    }

    @Test
    void summaryForPerfectDayPraisesUser() {
        tasksDoneYesterday(3, 3);
        clock.set(MSK_0800);

        String text = service.prepare(userId).orElseThrow().text();

        assertTrue(text.startsWith("Вчера выполнено 3 из 3 (100%)."), text);
        assertTrue(text.contains("Красава, день закрыт полностью."), text);
    }

    @Test
    void summaryRoundsPercent() {
        tasksDoneYesterday(3, 2);
        clock.set(MSK_0800);

        assertTrue(service.prepare(userId).orElseThrow().text().startsWith("Вчера выполнено 2 из 3 (67%)."));
    }

    @Test
    void summaryIsNotDueBeforeEightOrAfterCatchUpWindow() {
        tasksDoneYesterday(2, 1);

        clock.set(MSK_0759);
        assertTrue(service.prepare(userId).isEmpty());

        clock.set(MSK_0831); // окно 30 минут прошло
        assertTrue(service.prepare(userId).isEmpty());
    }

    @Test
    void summaryIsSentLateWithinCatchUpWindow() {
        tasksDoneYesterday(2, 1);
        clock.set(MSK_0820);

        assertEquals(NotificationKind.SUMMARY, service.prepare(userId).orElseThrow().kind());
    }

    @Test
    void summaryIsNotRepeatedOnceMarkedHandled() {
        tasksDoneYesterday(2, 1);
        clock.set(MSK_0800);
        PendingNotification n = service.prepare(userId).orElseThrow();

        service.markHandled(n.userId(), n.kind(), n.slot());

        assertTrue(service.prepare(userId).isEmpty());
        clock.set(MSK_0820);
        assertTrue(service.prepare(userId).isEmpty());
    }

    @Test
    void summaryIsRepeatedIfNotMarkedHandled() {
        tasksDoneYesterday(2, 1);
        clock.set(MSK_0800);
        service.prepare(userId).orElseThrow(); // отправка «не удалась» — слот не отмечен

        clock.set("2026-11-07T05:01:00Z");

        assertEquals(NotificationKind.SUMMARY, service.prepare(userId).orElseThrow().kind());
    }

    @Test
    void summaryHasNoTextWhenThereIsNothingToReport() {
        clock.set(MSK_0800); // нет ни вчерашних, ни сегодняшних дел

        PendingNotification n = service.prepare(userId).orElseThrow();

        assertNull(n.text());
        service.markHandled(n.userId(), n.kind(), n.slot());
        assertTrue(service.prepare(userId).isEmpty());
    }

    @Test
    void summaryUsesUserTimezoneNotServerOne() {
        tracker.register(CHAT + 1);
        User vladivostok = users.findByTelegramChatId(CHAT + 1).orElseThrow();
        vladivostok.setTimezone("Asia/Vladivostok"); // UTC+10: 08:00 у них = 22:00 UTC предыдущего дня
        clock.set("2026-11-06T22:00:00Z");

        assertEquals(NotificationKind.SUMMARY, service.prepare(vladivostok.getId()).orElseThrow().kind());
        assertTrue(service.prepare(userId).isEmpty()); // у москвича сейчас 01:00 ночи
    }

    // ---------- Напоминания ----------

    @Test
    void reminderListsNotDoneTasksWithQuote() {
        quotes.save(new Quote("Вставай и делай", "Марк Аврелий"));
        threeTasksOneDone();
        clock.set(MSK_1300);

        PendingNotification n = service.prepare(userId).orElseThrow();

        assertEquals(NotificationKind.REMINDER, n.kind());
        assertEquals(LocalDateTime.of(2026, 11, 7, 13, 0), n.slot());
        assertTrue(n.text().startsWith("Ты ещё не сделал 2 из 3. Вставай и делай."), n.text());
        assertTrue(n.text().contains("⬜ Б"), n.text());
        assertTrue(n.text().contains("⬜ В"), n.text());
        assertFalse(n.text().contains("⬜ А"), n.text());
        assertTrue(n.text().contains("«Вставай и делай» — Марк Аврелий"), n.text());
    }

    @Test
    void reminderWorksWithoutQuotesInDatabase() {
        threeTasksOneDone();
        clock.set(MSK_1300);

        String text = service.prepare(userId).orElseThrow().text();

        assertTrue(text.contains("⬜ Б"));
        assertFalse(text.contains("«"));
    }

    @Test
    void reminderHasNoTextWhenAllDone() {
        clock.set(MSK_1200);
        tracker.addTask(CHAT, "А");
        tracker.markAllDone(CHAT);
        clock.set(MSK_1300);

        PendingNotification n = service.prepare(userId).orElseThrow();

        assertEquals(NotificationKind.REMINDER, n.kind());
        assertNull(n.text()); // не беспокоим, но слот отмечаем обработанным
    }

    @Test
    void reminderHasNoTextWhenUserHasNoTasks() {
        clock.set(MSK_1300);

        assertNull(service.prepare(userId).orElseThrow().text());
    }

    @Test
    void reminderIsNotRepeatedInSameSlotButNextSlotIsDue() {
        threeTasksOneDone();
        clock.set(MSK_1300);
        PendingNotification first = service.prepare(userId).orElseThrow();
        service.markHandled(first.userId(), first.kind(), first.slot());

        clock.set("2026-11-07T10:05:00Z");
        assertTrue(service.prepare(userId).isEmpty());

        clock.set(MSK_1700);
        PendingNotification second = service.prepare(userId).orElseThrow();
        assertEquals(LocalDateTime.of(2026, 11, 7, 17, 0), second.slot());
        assertTrue(second.text().contains("Ты ещё не сделал 2 из 3"));
    }

    @Test
    void reminderIsNotDueOutsideCatchUpWindow() {
        threeTasksOneDone();

        clock.set(MSK_1200);
        assertTrue(service.prepare(userId).isEmpty());
        clock.set(MSK_1340);
        assertTrue(service.prepare(userId).isEmpty());
    }

    @Test
    void reminderTruncatesLongList() {
        clock.set(MSK_1200);
        for (int i = 1; i <= 17; i++) {
            tracker.addTask(CHAT, "Дело " + i);
        }
        clock.set(MSK_1300);

        String text = service.prepare(userId).orElseThrow().text();

        assertTrue(text.contains("⬜ Дело 15"));
        assertFalse(text.contains("⬜ Дело 16"));
        assertTrue(text.contains("…и ещё 2"), text);
    }

    @Test
    void unknownUserGivesNothing() {
        clock.set(MSK_0800);

        assertTrue(service.prepare(987654L).isEmpty());
    }

    @Test
    void userIdsContainsRegisteredUsers() {
        assertEquals(List.of(userId), service.userIds());
    }

    @Test
    void pluralPicksCorrectRussianForm() {
        assertEquals("дело", NotificationService.plural(1, "дело", "дела", "дел"));
        assertEquals("дела", NotificationService.plural(3, "дело", "дела", "дел"));
        assertEquals("дел", NotificationService.plural(5, "дело", "дела", "дел"));
        assertEquals("дел", NotificationService.plural(11, "дело", "дела", "дел"));
        assertEquals("дело", NotificationService.plural(21, "дело", "дела", "дел"));
        assertEquals("дел", NotificationService.plural(112, "дело", "дела", "дел"));
    }

    // ---------- Помощники ----------

    /** Вчера (в полдень) пользователь добавил total дел и выполнил done из них. */
    private void tasksDoneYesterday(int total, int done) {
        clock.set(YESTERDAY_NOON);
        for (int i = 1; i <= total; i++) {
            tracker.addTask(CHAT, "Дело " + i);
        }
        List<TodayItem> items = tracker.todayItems(CHAT);
        for (int i = 0; i < done; i++) {
            tracker.markDone(CHAT, items.get(i).entryId());
        }
    }

    /** Сегодня в 12:00 добавлены дела А, Б, В; А выполнено. */
    private void threeTasksOneDone() {
        clock.set(MSK_1200);
        tracker.addTask(CHAT, "А");
        tracker.addTask(CHAT, "Б");
        tracker.addTask(CHAT, "В");
        tracker.markDone(CHAT, tracker.todayItems(CHAT).get(0).entryId());
    }
}
