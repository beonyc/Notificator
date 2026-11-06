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
import com.example.notificator.service.ReminderSettingsService;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.stats.StatsService;
import com.example.notificator.support.MutableClock;
import com.example.notificator.support.NotificationTestConfig;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
@Import({NotificationTestConfig.class, TrackerService.class, QuoteService.class,
        ReminderSettingsService.class, StatsService.class, NotificationService.class})
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
    private ReminderSettingsService reminderSettings;
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
        assertTrue(n.text().contains("Вчера выполнено 1 из 2 (50%)."), n.text());
        assertTrue(n.text().contains("Не доделано: 1. Сегодня без отговорок."), n.text());
        assertTrue(n.text().contains("Сегодня в списке: 2 дела."), n.text());
        assertEquals(2, tracker.todayItems(CHAT).size()); // отметки на новый день созданы
    }

    @Test
    void summaryForPerfectDayPraisesUser() {
        tasksDoneYesterday(3, 3);
        clock.set(MSK_0800);

        String text = service.prepare(userId).orElseThrow().text();

        assertTrue(text.contains("Вчера выполнено 3 из 3 (100%)."), text);
        assertTrue(text.contains("Красава, день закрыт полностью."), text);
    }

    @Test
    void summaryRoundsPercent() {
        tasksDoneYesterday(3, 2);
        clock.set(MSK_0800);

        assertTrue(service.prepare(userId).orElseThrow().text().contains("Вчера выполнено 2 из 3 (67%)."));
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
        assertTrue(n.text().contains("Ты ещё не сделал 2 из 3. Вставай и делай."), n.text());
        assertTrue(n.text().contains("⬜ Б"), n.text());
        assertTrue(n.text().contains("⬜ В"), n.text());
        assertFalse(n.text().contains("⬜ А"), n.text());
        assertTrue(n.text().contains("💬 <i>«Вставай и делай»</i> — Марк Аврелий"), n.text());
    }

    @Test
    void reminderHasHeaderAndEscapesTaskTitlesAndQuote() {
        quotes.save(new Quote("Если a < b & c", "Автор <X>"));
        clock.set(MSK_1200);
        tracker.addTask(CHAT, "Сдать <отчёт> & Ко");
        clock.set(MSK_1300);

        String text = service.prepare(userId).orElseThrow().text();

        assertTrue(text.startsWith("⏰ <b>Время действовать!</b>\n"), text);
        assertTrue(text.contains("⬜ Сдать &lt;отчёт&gt; &amp; Ко"), text);
        assertTrue(text.contains("💬 <i>«Если a &lt; b &amp; c»</i> — Автор &lt;X&gt;"), text);
        assertFalse(text.contains("<отчёт>"), text);
    }

    @Test
    void summaryHasGreetingAndProgressBar() {
        tasksDoneYesterday(2, 1);
        clock.set(MSK_0800);

        String text = service.prepare(userId).orElseThrow().text();

        assertTrue(text.startsWith("🌅 <b>Доброе утро!</b>\n\n▰▰▰▰▰▱▱▱▱▱ 50%\n"), text);
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

    // ---------- Личные настройки напоминаний ----------

    @Test
    void remindersUseUsersOwnTimesInsteadOfDefaults() {
        reminderSettings.setTimes(CHAT, List.of(LocalTime.of(15, 0), LocalTime.of(22, 0)));
        clock.set(MSK_1200);
        tracker.addTask(CHAT, "А");

        clock.set(MSK_1300); // 13:00 — стандартное время, но у пользователя его больше нет
        assertTrue(service.prepare(userId).isEmpty());

        clock.set("2026-11-07T12:00:00Z"); // 15:00 МСК — своё время
        PendingNotification n = service.prepare(userId).orElseThrow();
        assertEquals(LocalDateTime.of(2026, 11, 7, 15, 0), n.slot());
        assertTrue(n.text().contains("Ты ещё не сделал 1 из 1"), n.text());
    }

    @Test
    void numberOfRemindersEqualsNumberOfUserTimes() {
        reminderSettings.setTimes(CHAT, List.of(LocalTime.of(15, 0)));
        clock.set(MSK_1200);
        tracker.addTask(CHAT, "А");

        clock.set("2026-11-07T12:00:00Z"); // 15:00 — единственное напоминание
        PendingNotification only = service.prepare(userId).orElseThrow();
        service.markHandled(only.userId(), only.kind(), only.slot());

        clock.set(MSK_1700); // 17:00 и 21:00 больше не напоминают
        assertTrue(service.prepare(userId).isEmpty());
        clock.set("2026-11-07T18:00:00Z"); // 21:00
        assertTrue(service.prepare(userId).isEmpty());
    }

    @Test
    void disabledRemindersAreNotSentButSummaryStillIs() {
        tasksDoneYesterday(2, 1);
        reminderSettings.setEnabled(CHAT, false);

        clock.set(MSK_0800);
        PendingNotification summary = service.prepare(userId).orElseThrow();
        assertEquals(NotificationKind.SUMMARY, summary.kind());
        service.markHandled(summary.userId(), summary.kind(), summary.slot());

        for (String time : new String[] {MSK_1300, MSK_1700, "2026-11-07T18:00:00Z"}) {
            clock.set(time);
            assertTrue(service.prepare(userId).isEmpty(), "напоминание в " + time);
        }
    }

    @Test
    void reEnablingBringsBackSavedCustomTimes() {
        reminderSettings.setTimes(CHAT, List.of(LocalTime.of(15, 0)));
        reminderSettings.setEnabled(CHAT, false);
        reminderSettings.setEnabled(CHAT, true);
        clock.set(MSK_1200);
        tracker.addTask(CHAT, "А");

        clock.set("2026-11-07T12:00:00Z");

        assertEquals(LocalDateTime.of(2026, 11, 7, 15, 0), service.prepare(userId).orElseThrow().slot());
    }

    @Test
    void resetToDefaultRestoresStandardTimes() {
        reminderSettings.setTimes(CHAT, List.of(LocalTime.of(15, 0)));
        reminderSettings.resetToDefault(CHAT);
        clock.set(MSK_1200);
        tracker.addTask(CHAT, "А");

        clock.set(MSK_1300);

        assertEquals(LocalDateTime.of(2026, 11, 7, 13, 0), service.prepare(userId).orElseThrow().slot());
    }

    @Test
    void changingTimesDoesNotTriggerImmediateLateReminder() {
        clock.set(MSK_1200);
        tracker.addTask(CHAT, "А");

        clock.set("2026-11-07T11:40:00Z"); // 14:40 МСК: пользователь ставит напоминание на 14:30
        reminderSettings.setTimes(CHAT, List.of(LocalTime.of(14, 30)));

        assertTrue(service.prepare(userId).isEmpty()); // не «с опозданием на 10 минут»
    }

    @Test
    void remindersFollowUsersTimezoneForCustomTimes() {
        tracker.register(CHAT + 1);
        User vladivostok = users.findByTelegramChatId(CHAT + 1).orElseThrow();
        vladivostok.setTimezone("Asia/Vladivostok"); // UTC+10
        clock.set("2026-11-06T22:30:00Z"); // 08:30 у них — день уже начался
        reminderSettings.setTimes(CHAT + 1, List.of(LocalTime.of(9, 0)));
        tracker.addTask(CHAT + 1, "Утреннее");

        clock.set("2026-11-06T23:00:00Z"); // 09:00 по Владивостоку

        PendingNotification n = service.prepare(vladivostok.getId()).orElseThrow();
        assertEquals(NotificationKind.REMINDER, n.kind());
        assertEquals(LocalDateTime.of(2026, 11, 7, 9, 0), n.slot());
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

    // ---------- Недельный отчёт ----------
    // Понедельник 9 ноября 2026, 00:05 МСК = 8 ноября 21:05 UTC. Прошедшая неделя: 2–8 ноября.

    private static final String MON_0005 = "2026-11-08T21:05:00Z";
    private static final String MON_0004 = "2026-11-08T21:04:00Z";
    private static final String MON_0036 = "2026-11-08T21:36:00Z";
    private static final String TUE_0005 = "2026-11-09T21:05:00Z";

    /** В указанный день недели прошлой недели (UTC-полдень) добавлено дело, и выполнено ли оно. */
    private void entryOnDay(String taskTitle, String noonUtc, boolean done) {
        clock.set(noonUtc);
        tracker.addTask(CHAT, taskTitle);
        if (done) {
            tracker.markAllDone(CHAT);
        }
    }

    @Test
    void weeklyReportOnMondayShortlyAfterMidnightSummarisesPreviousWeek() {
        entryOnDay("Читать", "2026-11-03T09:00:00Z", true);   // вторник — выполнено
        entryOnDay("Зарядка", "2026-11-05T09:00:00Z", false); // четверг — нет
        clock.set(MON_0005);

        PendingNotification n = service.prepare(userId).orElseThrow();

        assertEquals(NotificationKind.WEEKLY_REPORT, n.kind());
        assertEquals(LocalDateTime.of(2026, 11, 9, 0, 5), n.slot());
        assertTrue(n.text().startsWith("📊 <b>Итоги недели</b> 02.11–08.11"), n.text());
        assertTrue(n.text().contains("Выполнено <b>1</b> из <b>2</b>"), n.text());
        assertTrue(n.text().contains("• Читать — 1 из 1"), n.text());
    }

    @Test
    void weeklyReportIsNotDueOnOtherWeekdaysOrOutsideWindow() {
        entryOnDay("Читать", "2026-11-03T09:00:00Z", true);

        clock.set(MON_0004); // за минуту до времени отчёта
        assertTrue(service.prepare(userId).isEmpty());
        clock.set(MON_0036); // окно 30 минут прошло
        assertTrue(service.prepare(userId).isEmpty());
        clock.set(TUE_0005); // вторник
        assertTrue(service.prepare(userId).isEmpty());
        clock.set("2026-11-07T21:05:00Z"); // воскресенье 00:05 — не понедельник
        assertTrue(service.prepare(userId).isEmpty());
    }

    @Test
    void weeklyReportIsNotRepeatedOnceMarkedHandled() {
        entryOnDay("Читать", "2026-11-03T09:00:00Z", true);
        clock.set(MON_0005);
        PendingNotification n = service.prepare(userId).orElseThrow();

        service.markHandled(n.userId(), n.kind(), n.slot());

        clock.set("2026-11-08T21:10:00Z");
        assertTrue(service.prepare(userId).isEmpty());
    }

    @Test
    void weeklyReportHasNoTextWhenWeekHadNoTasks() {
        clock.set(MON_0005);

        PendingNotification n = service.prepare(userId).orElseThrow();

        assertEquals(NotificationKind.WEEKLY_REPORT, n.kind());
        assertNull(n.text());
        service.markHandled(n.userId(), n.kind(), n.slot());
        assertTrue(service.prepare(userId).isEmpty());
    }

    @Test
    void weeklyReportDoesNotDependOnReminderSettings() {
        entryOnDay("Читать", "2026-11-03T09:00:00Z", true);
        reminderSettings.setEnabled(CHAT, false);
        clock.set(MON_0005);

        assertEquals(NotificationKind.WEEKLY_REPORT, service.prepare(userId).orElseThrow().kind());
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
