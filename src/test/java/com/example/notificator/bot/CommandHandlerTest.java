package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.notificator.service.EditResult;
import com.example.notificator.service.MarkResult;
import com.example.notificator.stats.DayReport;
import com.example.notificator.stats.DayStat;
import com.example.notificator.stats.StatsService;
import com.example.notificator.stats.WeekOverview;
import com.example.notificator.stats.WeeklyStats;
import java.time.LocalDate;
import com.example.notificator.service.ReminderSettings;
import com.example.notificator.service.ReminderSettingsService;
import java.time.LocalTime;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import com.example.notificator.service.TaskItem;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Юнит-тесты разбора команд и нажатий кнопок. Сервис — мок, БД и Telegram не нужны. */
class CommandHandlerTest {

    private static final long CHAT = 42L;

    private TrackerService service;
    private ReminderSettingsService reminders;
    private StatsService stats;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(TrackerService.class);
        reminders = mock(ReminderSettingsService.class);
        stats = mock(StatsService.class);
        handler = new CommandHandler(service, reminders, stats);
    }

    @Test
    void startRegistersNewUser() {
        when(service.register(CHAT)).thenReturn(true);

        assertTrue(handler.handle(CHAT, "/start").text().contains("Привет!"));
    }

    @Test
    void startForExistingUserSaysAlreadyRegistered() {
        when(service.register(CHAT)).thenReturn(false);

        assertTrue(handler.handle(CHAT, "/start").text().contains("Ты уже зарегистрирован"));
    }

    @Test
    void addSavesTaskWithFullTitle() {
        BotReply answer = handler.handle(CHAT, "/add  Читать 30 минут ");

        verify(service).addTask(CHAT, "Читать 30 минут");
        assertEquals("✅ Добавлено: <b>Читать 30 минут</b>", answer.text());
    }

    @Test
    void addWithoutTitleAsksForTitle() {
        BotReply answer = handler.handle(CHAT, "/add");

        verify(service, never()).addTask(anyLong(), anyString());
        assertTrue(answer.text().contains("Напиши название дела"));
    }

    @Test
    void addWithoutTitleShowsSeveralExamplesEachOnItsOwnLine() {
        List<String> lines = handler.handle(CHAT, "/add").text().lines().toList();

        List<String> examples = lines.stream().filter(l -> l.startsWith("<code>/add ")).toList();
        assertEquals(List.of(
                "<code>/add Читать 30 минут</code>",
                "<code>/add Сделать зарядку</code>",
                "<code>/add Выучить 10 английских слов</code>",
                "<code>/add Позвонить родителям</code>"), examples);
        // примеры не слиты в одну строку: в каждой строке ровно одна команда
        assertTrue(lines.stream().allMatch(l -> l.split("/add", -1).length <= 2), lines.toString());
    }

    @Test
    void helpListsAddCommandWithoutExampleTitle() {
        when(service.register(CHAT)).thenReturn(false);
        String unknown = handler.handle(CHAT, "привет").text();
        String start = handler.handle(CHAT, "/start").text();

        for (String text : new String[] {CommandHandler.HELP, unknown, start}) {
            assertTrue(text.contains("➕ /add — добавить дело"), text);
            assertFalse(text.contains("/add Читать"), text);
        }
    }

    @Test
    void addWithBlankTitleAlsoShowsExamples() {
        assertTrue(handler.handle(CHAT, "/add    ").text().contains("<code>/add Сделать зарядку</code>"));
    }

    @Test
    void addRejectsTooLongTitle() {
        BotReply answer = handler.handle(CHAT, "/add " + "я".repeat(CommandHandler.MAX_TITLE_LENGTH + 1));

        verify(service, never()).addTask(anyLong(), anyString());
        assertTrue(answer.text().contains("Слишком длинное название"));
    }

    @Test
    void commandWithBotNameSuffixAndUpperCaseIsRecognized() {
        when(service.todayItems(CHAT)).thenReturn(List.of());

        assertTrue(handler.handle(CHAT, "/TODAY@NotificatorBot").text().contains("На сегодня дел нет"));
    }

    @Test
    void todayShowsListAndButtonOnlyForNotDoneTasks() {
        when(service.todayItems(CHAT)).thenReturn(List.of(
                new TodayItem(10, "Читать", true), new TodayItem(11, "Зарядка", false)));

        BotReply reply = handler.handle(CHAT, "/today");

        assertEquals("""
                📋 <b>Дела на сегодня</b>

                ✅ 1. <s>Читать</s>
                ⬜ 2. Зарядка

                ▰▰▰▰▰▱▱▱▱▱ 50%
                Выполнено <b>1</b> из <b>2</b>""", reply.text());
        assertEquals(List.of(new ReplyButton("✅ 2. Зарядка", "done:11")), reply.buttons());
    }

    @Test
    void todayWithAllDoneHasNoButtons() {
        when(service.todayItems(CHAT)).thenReturn(List.of(new TodayItem(10, "Читать", true)));

        BotReply reply = handler.handle(CHAT, "/today");

        assertTrue(reply.buttons().isEmpty());
        assertTrue(reply.text().endsWith("🎉 Все дела выполнены, день закрыт 💪"));
        assertTrue(reply.text().contains("▰▰▰▰▰▰▰▰▰▰ 100%"), reply.text());
    }

    @Test
    void userTextInTitlesIsHtmlEscapedSoTelegramCanParseMessage() {
        when(service.todayItems(CHAT)).thenReturn(List.of(new TodayItem(1, "Сдать <отчёт> & Ко", false)));
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(7, "Сдать <отчёт> & Ко")));

        assertTrue(handler.handle(CHAT, "/today").text().contains("⬜ 1. Сдать &lt;отчёт&gt; &amp; Ко"));
        assertTrue(handler.handle(CHAT, "/remove").text().contains("1. Сдать &lt;отчёт&gt; &amp; Ко"));
        assertEquals("✅ Добавлено: <b>&lt;b&gt;хак&lt;/b&gt;</b>", handler.handle(CHAT, "/add <b>хак</b>").text());
    }

    @Test
    void doneTasksAreStruckThroughInTodayList() {
        when(service.todayItems(CHAT)).thenReturn(List.of(new TodayItem(1, "Читать", true)));

        assertTrue(handler.handle(CHAT, "/today").text().contains("✅ 1. <s>Читать</s>"));
    }

    @Test
    void longTitleIsShortenedOnButton() {
        when(service.todayItems(CHAT)).thenReturn(List.of(new TodayItem(1, "а".repeat(100), false)));

        String buttonText = handler.handle(CHAT, "/today").buttons().get(0).text();

        assertEquals("✅ 1. " + "а".repeat(29) + "…", buttonText);
    }

    @Test
    void unregisteredUserIsAskedToPressStart() {
        when(service.todayItems(CHAT)).thenThrow(new UserNotRegisteredException(CHAT));

        assertEquals("👋 Сначала нажми /start.", handler.handle(CHAT, "/today").text());
    }

    @Test
    void unknownTextShowsHelp() {
        assertTrue(handler.handle(CHAT, "привет").text().contains("/today"));
    }

    @Test
    void doneButtonMarksTaskAndReturnsRefreshedList() {
        when(service.markDone(CHAT, 11L)).thenReturn(MarkResult.DONE);
        when(service.todayItems(CHAT)).thenReturn(List.of(new TodayItem(11, "Зарядка", true)));

        CallbackResponse response = handler.handleCallback(CHAT, "done:11");

        verify(service).markDone(CHAT, 11L);
        assertEquals("Выполнено ✅", response.notice());
        assertTrue(response.view().text().contains("✅ 1. <s>Зарядка</s>"));
        assertTrue(response.view().buttons().isEmpty());
    }

    @Test
    void doneButtonNoticeDependsOnResult() {
        when(service.todayItems(CHAT)).thenReturn(List.of());
        when(service.markDone(CHAT, 1L)).thenReturn(MarkResult.ALREADY_DONE);
        when(service.markDone(CHAT, 2L)).thenReturn(MarkResult.EXPIRED);
        when(service.markDone(CHAT, 3L)).thenReturn(MarkResult.NOT_FOUND);

        assertEquals("Это дело уже выполнено", handler.handleCallback(CHAT, "done:1").notice());
        assertEquals("Этот день уже закончился", handler.handleCallback(CHAT, "done:2").notice());
        assertEquals("Дело не найдено", handler.handleCallback(CHAT, "done:3").notice());
    }

    @Test
    void garbageCallbackDataIsIgnoredWithoutTouchingService() {
        for (String data : new String[] {null, "hack", "done:abc", "done:"}) {
            CallbackResponse response = handler.handleCallback(CHAT, data);

            assertEquals("Неизвестное действие", response.notice());
            assertNull(response.view());
        }
        verify(service, never()).markDone(anyLong(), anyLong());
    }

    @Test
    void removeShowsActiveTasksWithDeleteButtons() {
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(7, "Читать"), new TaskItem(8, "Зарядка")));

        BotReply reply = handler.handle(CHAT, "/remove");

        assertEquals("""
                🗑 <b>Какое дело убрать из списка?</b>

                1. Читать
                2. Зарядка""", reply.text());
        assertEquals(List.of(new ReplyButton("🗑 1. Читать", "rm:7"), new ReplyButton("🗑 2. Зарядка", "rm:8"),
                new ReplyButton("🗑 Удалить все", "rmall")), reply.buttons());
    }

    @Test
    void removeAllButtonIsNotShownForSingleTask() {
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(7, "Читать")));

        assertEquals(List.of(new ReplyButton("🗑 1. Читать", "rm:7")), handler.handle(CHAT, "/remove").buttons());
    }

    @Test
    void todayShowsDoneAllButtonWhenTwoOrMoreNotDone() {
        when(service.todayItems(CHAT)).thenReturn(List.of(
                new TodayItem(1, "Читать", false), new TodayItem(2, "Зарядка", false), new TodayItem(3, "Сон", true)));

        List<ReplyButton> buttons = handler.handle(CHAT, "/today").buttons();

        assertEquals(new ReplyButton("✅ Выполнить все", "doneall"), buttons.get(buttons.size() - 1));
        assertEquals(3, buttons.size());
    }

    @Test
    void todayHasNoDoneAllButtonWhenOnlyOneNotDone() {
        when(service.todayItems(CHAT)).thenReturn(List.of(
                new TodayItem(1, "Читать", false), new TodayItem(2, "Зарядка", true)));

        assertEquals(1, handler.handle(CHAT, "/today").buttons().size());
    }

    @Test
    void doneAllMarksEverythingAndRefreshesList() {
        when(service.markAllDone(CHAT)).thenReturn(3);
        when(service.todayItems(CHAT)).thenReturn(List.of(new TodayItem(1, "Читать", true)));

        CallbackResponse response = handler.handleCallback(CHAT, "doneall");

        verify(service).markAllDone(CHAT);
        assertEquals("Выполнено дел: 3 ✅", response.notice());
        assertTrue(response.view().buttons().isEmpty());
    }

    @Test
    void doneAllWhenNothingToMarkSaysAllDone() {
        when(service.markAllDone(CHAT)).thenReturn(0);
        when(service.todayItems(CHAT)).thenReturn(List.of());

        assertEquals("Все дела уже выполнены", handler.handleCallback(CHAT, "doneall").notice());
    }

    @Test
    void removeAllAsksConfirmationAndDoesNotDeleteYet() {
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(7, "Читать"), new TaskItem(8, "Зарядка")));

        CallbackResponse response = handler.handleCallback(CHAT, "rmall");

        verify(service, never()).removeAllTasks(anyLong());
        assertTrue(response.view().text().contains("Удалить все дела (2)?"));
        assertEquals(List.of(new ReplyButton("🗑 Да, удалить все", "rmall:yes"),
                new ReplyButton("↩️ Отмена", "rmall:no")), response.view().buttons());
    }

    @Test
    void removeAllConfirmedDeletesEverything() {
        when(service.removeAllTasks(CHAT)).thenReturn(2);
        when(service.activeTasks(CHAT)).thenReturn(List.of());

        CallbackResponse response = handler.handleCallback(CHAT, "rmall:yes");

        verify(service).removeAllTasks(CHAT);
        assertEquals("Удалено дел: 2", response.notice());
        assertTrue(response.view().text().contains("Дел для удаления нет"));
    }

    @Test
    void removeAllCancelledKeepsTasksAndShowsListAgain() {
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(7, "Читать"), new TaskItem(8, "Зарядка")));

        CallbackResponse response = handler.handleCallback(CHAT, "rmall:no");

        verify(service, never()).removeAllTasks(anyLong());
        assertEquals("Отменено", response.notice());
        assertTrue(response.view().text().contains("Какое дело убрать"));
    }

    @Test
    void removeWithNoTasksSaysNothingToRemove() {
        when(service.activeTasks(CHAT)).thenReturn(List.of());

        BotReply reply = handler.handle(CHAT, "/remove");

        assertTrue(reply.text().contains("Дел для удаления нет"));
        assertTrue(reply.buttons().isEmpty());
    }

    @Test
    void removeButtonDeletesTaskAndReturnsRefreshedList() {
        when(service.removeTask(CHAT, 7L)).thenReturn(Optional.of("Читать"));
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(8, "Зарядка")));

        CallbackResponse response = handler.handleCallback(CHAT, "rm:7");

        verify(service).removeTask(CHAT, 7L);
        assertEquals("Удалено: Читать", response.notice());
        assertEquals(List.of(new ReplyButton("🗑 1. Зарядка", "rm:8")), response.view().buttons());
    }

    @Test
    void removeButtonForMissingTaskSaysNotFound() {
        when(service.removeTask(CHAT, 7L)).thenReturn(Optional.empty());
        when(service.activeTasks(CHAT)).thenReturn(List.of());

        assertEquals("Дело не найдено", handler.handleCallback(CHAT, "rm:7").notice());
    }

    @Test
    void garbageRemoveCallbackIsIgnored() {
        assertEquals("Неизвестное действие", handler.handleCallback(CHAT, "rm:abc").notice());
        verify(service, never()).removeTask(anyLong(), anyLong());
    }

    // ---------- /week ----------

    @Test
    void weekShowsCurrentWeekStatistics() {
        when(stats.currentWeek(CHAT)).thenReturn(new WeeklyStats(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 7),
                0, 0, 0, List.of(), List.of(), null, null, 0, null, 0, "Проленился"));

        BotReply reply = handler.handle(CHAT, "/week");

        assertTrue(reply.text().startsWith("📊 <b>Неделя в процессе</b> 02.11–07.11"), reply.text());
        assertEquals(List.of(new ReplyButton("📅 Дела по дням", "days")), reply.buttons());
    }

    // ---------- Дела по дням (доступны только из /week) ----------

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate THU = LocalDate.of(2026, 11, 5);

    /** Четверг 5 ноября: пн 2 из 3, вт дел нет, ср 1 из 1, чт (сегодня) 0 из 2. */
    private static WeekOverview overview() {
        return new WeekOverview(MON, THU, List.of(
                new DayStat(MON, 3, 2), new DayStat(MON.plusDays(1), 0, 0),
                new DayStat(MON.plusDays(2), 1, 1), new DayStat(THU, 2, 0)));
    }

    private void givenDay(LocalDate date, DayReport.Status status, boolean today, TodayItem... items) {
        when(stats.dayReport(CHAT, date)).thenReturn(new DayReport(date, status, List.of(items), today, false));
    }

    /** Вчерашний день: его статусы можно исправлять. */
    private void givenEditableDay(LocalDate date, TodayItem... items) {
        when(stats.dayReport(CHAT, date)).thenReturn(
                new DayReport(date, DayReport.Status.OK, List.of(items), false, true));
    }

    @Test
    void dayCommandNoLongerExistsPastDaysAreOnlyAvailableFromWeek() {
        for (String text : new String[] {"/day", "/day пн", "/day 5.11"}) {
            assertTrue(handler.handle(CHAT, text).text().startsWith("🤔 Не понял."), text);
        }
        assertFalse(CommandHandler.HELP.contains("/day"));
        verify(stats, never()).dayReport(anyLong(), any());
        verify(stats, never()).weekOverview(anyLong());
    }

    @Test
    void daysButtonShowsPickerWithOneButtonPerDayAndBackToReport() {
        when(stats.weekOverview(CHAT)).thenReturn(overview());

        CallbackResponse response = handler.handleCallback(CHAT, "days");

        assertNull(response.notice());
        assertEquals("📅 <b>Выбери день этой недели</b> (02.11–08.11):", response.view().text());
        assertEquals(List.of(
                new ReplyButton("Пн 02.11 · 2 из 3", "day:2026-11-02"),
                new ReplyButton("Вт 03.11 · дел нет", "day:2026-11-03"),
                new ReplyButton("Ср 04.11 (вчера) · 1 из 1", "day:2026-11-04"),
                new ReplyButton("Чт 05.11 (сегодня) · 0 из 2", "day:2026-11-05"),
                new ReplyButton("↩️ К отчёту", "week")), response.view().buttons());
    }

    @Test
    void backToReportButtonReturnsWeekReportWithDaysButton() {
        when(stats.currentWeek(CHAT)).thenReturn(new WeeklyStats(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5),
                0, 0, 0, List.of(), List.of(), null, null, 0, null, 0, "Проленился"));

        CallbackResponse response = handler.handleCallback(CHAT, "week");

        assertNull(response.notice());
        assertTrue(response.view().text().startsWith("📊 <b>Неделя в процессе</b> 02.11–05.11"), response.view().text());
        assertEquals(List.of(new ReplyButton("📅 Дела по дням", "days")), response.view().buttons());
    }

    @Test
    void weekReportFromCommandAndFromBackButtonAreTheSame() {
        when(stats.currentWeek(CHAT)).thenReturn(new WeeklyStats(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5),
                0, 0, 0, List.of(), List.of(), null, null, 0, null, 0, "Проленился"));

        assertEquals(handler.handle(CHAT, "/week"), handler.handleCallback(CHAT, "week").view());
    }

    @Test
    void dayButtonShowsPastDayWithDoneAndMissedTasksAndBackToDays() {
        givenDay(MON, DayReport.Status.OK, false,
                new TodayItem(1, "Читать", true), new TodayItem(2, "Зарядка", false), new TodayItem(3, "Сон", true));

        CallbackResponse response = handler.handleCallback(CHAT, "day:2026-11-02");

        assertNull(response.notice());
        assertEquals("""
                📅 <b>Понедельник, 02.11</b>

                ✅ Читать
                ❌ Зарядка
                ✅ Сон

                ▰▰▰▰▰▰▰▱▱▱ 67%
                Выполнено <b>2</b> из <b>3</b>""", response.view().text());
        assertEquals(List.of(new ReplyButton("↩️ К дням недели", "days")), response.view().buttons());
    }

    @Test
    void dayViewOfTodayUsesPendingMarkAndPointsToToday() {
        givenDay(THU, DayReport.Status.OK, true, new TodayItem(5, "Читать", false), new TodayItem(6, "Сон", true));

        String text = handler.handleCallback(CHAT, "day:2026-11-05").view().text();

        assertTrue(text.startsWith("📅 <b>Четверг, 05.11</b> (сегодня)"), text);
        assertTrue(text.contains("⬜ Читать"), text);
        assertFalse(text.contains("❌"), text);
        assertTrue(text.contains("▰▰▰▰▰▱▱▱▱▱ 50%"), text);
        assertTrue(text.endsWith("💡 Отмечать дела: /today"), text);
    }

    @Test
    void dayWithNoTasksSaysSo() {
        givenDay(MON.plusDays(1), DayReport.Status.OK, false);

        String text = handler.handleCallback(CHAT, "day:2026-11-03").view().text();

        assertEquals("📅 <b>Вторник, 03.11</b>\n\n📭 В этот день дел не было.", text);
    }

    // ---------- Исправление статусов за вчера ----------

    private static final LocalDate WED = LocalDate.of(2026, 11, 4); // «вчера» при сегодня = четверг 5.11

    @Test
    void yesterdayViewOffersToggleButtonPerTaskAndExplainsWhy() {
        givenEditableDay(WED, new TodayItem(1, "Читать", true), new TodayItem(2, "Зарядка", false));

        BotReply view = handler.handleCallback(CHAT, "day:2026-11-04").view();

        assertEquals("""
                📅 <b>Среда, 04.11</b> (вчера)

                ✅ Читать
                ❌ Зарядка

                ▰▰▰▰▰▱▱▱▱▱ 50%
                Выполнено <b>1</b> из <b>2</b>

                💡 Не успел вчера? Нажми на дело ниже, чтобы изменить его статус.""", view.text());
        assertEquals(List.of(
                new ReplyButton("↩️ Снять: Читать", "yd:2026-11-04:1:0"),
                new ReplyButton("✅ Отметить: Зарядка", "yd:2026-11-04:2:1"),
                new ReplyButton("↩️ К дням недели", "days")), view.buttons());
    }

    @Test
    void yesterdayButtonTitlesAreShortened() {
        givenEditableDay(WED, new TodayItem(1, "я".repeat(100), false));

        String title = handler.handleCallback(CHAT, "day:2026-11-04").view().buttons().get(0).text();

        assertEquals("✅ Отметить: " + "я".repeat(29) + "…", title);
    }

    @Test
    void yesterdayWithoutTasksHasNoEditButtons() {
        givenEditableDay(WED);

        BotReply view = handler.handleCallback(CHAT, "day:2026-11-04").view();

        assertTrue(view.text().contains("В этот день дел не было."));
        assertEquals(List.of(new ReplyButton("↩️ К дням недели", "days")), view.buttons());
    }

    @Test
    void olderDaysAndTodayHaveNoEditButtons() {
        givenDay(MON, DayReport.Status.OK, false, new TodayItem(1, "Читать", false));
        givenDay(THU, DayReport.Status.OK, true, new TodayItem(2, "Сон", false));

        assertEquals(List.of(new ReplyButton("↩️ К дням недели", "days")),
                handler.handleCallback(CHAT, "day:2026-11-02").view().buttons());
        assertEquals(List.of(new ReplyButton("↩️ К дням недели", "days")),
                handler.handleCallback(CHAT, "day:2026-11-05").view().buttons());
    }

    @Test
    void editButtonMarksYesterdayTaskDoneAndRefreshesView() {
        when(service.setYesterdayDone(CHAT, 2L, true)).thenReturn(EditResult.MARKED_DONE);
        givenEditableDay(WED, new TodayItem(1, "Читать", true), new TodayItem(2, "Зарядка", true));

        CallbackResponse response = handler.handleCallback(CHAT, "yd:2026-11-04:2:1");

        verify(service).setYesterdayDone(CHAT, 2L, true);
        assertEquals("Отмечено ✅", response.notice());
        assertTrue(response.view().text().contains("Выполнено <b>2</b> из <b>2</b>"), response.view().text());
    }

    @Test
    void editButtonCanUndoMistakenMark() {
        when(service.setYesterdayDone(CHAT, 1L, false)).thenReturn(EditResult.MARKED_UNDONE);
        givenEditableDay(WED, new TodayItem(1, "Читать", false));

        CallbackResponse response = handler.handleCallback(CHAT, "yd:2026-11-04:1:0");

        verify(service).setYesterdayDone(CHAT, 1L, false);
        assertEquals("Отметка снята", response.notice());
    }

    @Test
    void editButtonNoticeDependsOnResult() {
        givenEditableDay(WED);
        when(service.setYesterdayDone(CHAT, 1L, true)).thenReturn(EditResult.UNCHANGED);
        when(service.setYesterdayDone(CHAT, 2L, true)).thenReturn(EditResult.NOT_EDITABLE);
        when(service.setYesterdayDone(CHAT, 3L, true)).thenReturn(EditResult.NOT_FOUND);

        assertEquals("Статус уже такой", handler.handleCallback(CHAT, "yd:2026-11-04:1:1").notice());
        assertEquals("Исправлять можно только вчерашний день", handler.handleCallback(CHAT, "yd:2026-11-04:2:1").notice());
        assertEquals("Дело не найдено", handler.handleCallback(CHAT, "yd:2026-11-04:3:1").notice());
    }

    @Test
    void editButtonWithGarbageDataIsIgnoredWithoutTouchingService() {
        for (String data : new String[] {"yd:", "yd:2026-11-04:2", "yd:x:2:1", "yd:2026-11-04:abc:1",
                "yd:2026-11-04:2:2", "yd:2026-11-04:2:1:extra", "yd:2026-13-45:2:1"}) {
            assertEquals("Неизвестное действие", handler.handleCallback(CHAT, data).notice(), data);
        }
        verify(service, never()).setYesterdayDone(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void yesterdayEditFromUnregisteredUserAsksToPressStart() {
        when(service.setYesterdayDone(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new UserNotRegisteredException(CHAT));

        assertEquals("Сначала нажми /start", handler.handleCallback(CHAT, "yd:2026-11-04:1:1").notice());
    }

    @Test
    void pickerOnMondayShowsYesterdaySundayFirst() {
        LocalDate sunday = LocalDate.of(2026, 11, 8);
        LocalDate monday = LocalDate.of(2026, 11, 9);
        when(stats.weekOverview(CHAT)).thenReturn(new WeekOverview(monday, monday,
                List.of(new DayStat(sunday, 2, 1), new DayStat(monday, 0, 0))));

        List<ReplyButton> buttons = handler.handleCallback(CHAT, "days").view().buttons();

        assertEquals(List.of(
                new ReplyButton("Вс 08.11 (вчера) · 1 из 2", "day:2026-11-08"),
                new ReplyButton("Пн 09.11 (сегодня) · дел нет", "day:2026-11-09"),
                new ReplyButton("↩️ К отчёту", "week")), buttons);
    }

    @Test
    void dayButtonWithRefusedDateShowsNoticeAndPicker() {
        when(stats.weekOverview(CHAT)).thenReturn(overview());
        givenDay(LocalDate.of(2026, 10, 20), DayReport.Status.OUT_OF_WEEK, false);
        givenDay(LocalDate.of(2026, 11, 6), DayReport.Status.FUTURE, false);

        CallbackResponse outside = handler.handleCallback(CHAT, "day:2026-10-20");
        CallbackResponse future = handler.handleCallback(CHAT, "day:2026-11-06");

        assertEquals("Можно смотреть только текущую неделю (02.11–08.11).", outside.notice());
        assertEquals("Этот день ещё не наступил.", future.notice());
        assertTrue(outside.view().text().startsWith("📅 <b>Выбери день"), outside.view().text());
    }

    @Test
    void dayButtonWithGarbageDataIsIgnored() {
        for (String data : new String[] {"day:", "day:abc", "day:2026-13-45", "day:2026-11-02;DROP"}) {
            assertEquals("Неизвестное действие", handler.handleCallback(CHAT, data).notice());
        }
        verify(stats, never()).dayReport(anyLong(), any());
    }

    @Test
    void daysButtonsFromUnregisteredUserAskToPressStart() {
        when(stats.weekOverview(CHAT)).thenThrow(new UserNotRegisteredException(CHAT));
        when(stats.dayReport(anyLong(), any())).thenThrow(new UserNotRegisteredException(CHAT));
        when(stats.currentWeek(CHAT)).thenThrow(new UserNotRegisteredException(CHAT));

        assertEquals("Сначала нажми /start", handler.handleCallback(CHAT, "days").notice());
        assertEquals("Сначала нажми /start", handler.handleCallback(CHAT, "day:2026-11-02").notice());
        assertEquals("Сначала нажми /start", handler.handleCallback(CHAT, "week").notice());
    }

    @Test
    void weekFromUnregisteredUserAsksToPressStart() {
        when(stats.currentWeek(CHAT)).thenThrow(new UserNotRegisteredException(CHAT));

        assertEquals("👋 Сначала нажми /start.", handler.handle(CHAT, "/week").text());
    }

    // ---------- /reminders ----------

    private static ReminderSettings settings(boolean enabled, boolean custom, LocalTime... times) {
        return new ReminderSettings(enabled, List.of(times), custom, "Europe/Moscow");
    }

    @Test
    void remindersShowsDefaultScheduleWithOffButton() {
        when(reminders.get(CHAT)).thenReturn(settings(true, false, LocalTime.of(13, 0), LocalTime.of(17, 0)));

        BotReply reply = handler.handle(CHAT, "/reminders");

        assertTrue(reply.text().startsWith("🔔 <b>Напоминания:</b> 13:00, 17:00 (по умолчанию)"), reply.text());
        assertTrue(reply.text().contains("Europe/Moscow"));
        assertEquals(List.of(new ReplyButton("✏️ Изменить время", "rem:edit"),
                new ReplyButton("🔕 Выключить", "rem:off")), reply.buttons());
    }

    @Test
    void remindersShowsCustomScheduleWithResetButton() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 30)));

        BotReply reply = handler.handle(CHAT, "/reminders");

        assertTrue(reply.text().startsWith("🔔 <b>Напоминания:</b> 09:30\n"), reply.text());
        assertEquals(List.of(new ReplyButton("✏️ Изменить время", "rem:edit"),
                new ReplyButton("🔕 Выключить", "rem:off"),
                new ReplyButton("↩️ По умолчанию", "rem:default")), reply.buttons());
    }

    @Test
    void remindersWhenDisabledShowsOnButton() {
        when(reminders.get(CHAT)).thenReturn(settings(false, false, LocalTime.of(13, 0)));

        BotReply reply = handler.handle(CHAT, "/reminders");

        assertTrue(reply.text().startsWith("🔕 <b>Напоминания выключены.</b>"), reply.text());
        assertTrue(reply.text().contains("Сохранённое время: 13:00"));
        assertEquals(List.of(new ReplyButton("✏️ Изменить время", "rem:edit"),
                new ReplyButton("🔔 Включить", "rem:on")), reply.buttons());
    }

    @Test
    void editButtonAsksForTimesAndNextPlainMessageSetsThem() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0), LocalTime.of(18, 0)));

        CallbackResponse ask = handler.handleCallback(CHAT, "rem:edit");

        assertEquals("Жду время", ask.notice());
        assertTrue(ask.view().text().contains("Отправь время напоминаний</b> одним сообщением"), ask.view().text());
        assertTrue(ask.view().text().contains("Europe/Moscow"));
        assertEquals(List.of(new ReplyButton("↩️ Отмена", "rem:cancel")), ask.view().buttons());

        BotReply reply = handler.handle(CHAT, "9:00 18:00"); // обычное сообщение без команды

        verify(reminders).setTimes(CHAT, List.of(LocalTime.of(9, 0), LocalTime.of(18, 0)));
        assertTrue(reply.text().startsWith("✅ Готово, время напоминаний обновлено."), reply.text());
    }

    @Test
    void afterTimesAreSetPlainTextIsNoLongerTreatedAsTimes() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));
        handler.handleCallback(CHAT, "rem:edit");
        handler.handle(CHAT, "9:00");

        BotReply reply = handler.handle(CHAT, "привет");

        assertTrue(reply.text().startsWith("🤔 Не понял."), reply.text());
        verify(reminders, org.mockito.Mockito.times(1)).setTimes(anyLong(), any());
    }

    @Test
    void invalidTimesWhileWaitingKeepWaitingAndMentionCancel() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));
        handler.handleCallback(CHAT, "rem:edit");

        BotReply bad = handler.handle(CHAT, "утром и вечером");

        assertTrue(bad.text().contains("Не понял время «утром»"), bad.text());
        assertTrue(bad.text().contains("/cancel"));
        verify(reminders, never()).setTimes(anyLong(), any());

        handler.handle(CHAT, "8:30"); // исправленный вариант принимается без повторного нажатия кнопки
        verify(reminders).setTimes(CHAT, List.of(LocalTime.of(8, 30)));
    }

    @Test
    void cancelCommandAndCancelButtonStopWaiting() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));

        handler.handleCallback(CHAT, "rem:edit");
        assertEquals("↩️ Отменено.", handler.handle(CHAT, "/cancel").text());
        assertTrue(handler.handle(CHAT, "9:00").text().startsWith("🤔 Не понял."));

        handler.handleCallback(CHAT, "rem:edit");
        CallbackResponse cancelled = handler.handleCallback(CHAT, "rem:cancel");
        assertEquals("Отменено", cancelled.notice());
        assertTrue(cancelled.view().text().contains("Напоминания"));
        assertTrue(handler.handle(CHAT, "9:00").text().startsWith("🤔 Не понял."));

        assertEquals("🤷 Отменять нечего.", handler.handle(CHAT, "/cancel").text());
        verify(reminders, never()).setTimes(anyLong(), any());
    }

    @Test
    void anotherCommandWhileWaitingWorksAndStopsWaiting() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));
        when(service.todayItems(CHAT)).thenReturn(List.of());
        handler.handleCallback(CHAT, "rem:edit");

        BotReply today = handler.handle(CHAT, "/today");

        assertTrue(today.text().contains("На сегодня дел нет"), today.text());
        assertTrue(handler.handle(CHAT, "9:00").text().startsWith("🤔 Не понял.")); // ожидание сброшено
    }

    @Test
    void plainTextWithoutPressingEditButtonIsNotTreatedAsTimes() {
        BotReply reply = handler.handle(CHAT, "9:00 18:00");

        assertTrue(reply.text().startsWith("🤔 Не понял."), reply.text());
        verify(reminders, never()).setTimes(anyLong(), any());
    }

    @Test
    void editButtonFromUnregisteredUserAsksToPressStart() {
        when(reminders.get(CHAT)).thenThrow(new UserNotRegisteredException(CHAT));

        assertEquals("Сначала нажми /start", handler.handleCallback(CHAT, "rem:edit").notice());
        assertTrue(handler.handle(CHAT, "9:00").text().startsWith("🤔 Не понял.")); // ожидание не включилось
    }

    @Test
    void remindersSetsCustomTimesInDifferentFormats() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));

        BotReply reply = handler.handle(CHAT, "/reminders 9 14:30,20.15  7:05");

        verify(reminders).setTimes(CHAT, List.of(LocalTime.of(9, 0), LocalTime.of(14, 30),
                LocalTime.of(20, 15), LocalTime.of(7, 5)));
        assertTrue(reply.text().startsWith("✅ Готово, время напоминаний обновлено."), reply.text());
    }

    @Test
    void remindersRejectsInvalidTimeWithoutChangingAnything() {
        for (String bad : new String[] {"25:00", "9:60", "abc", "9:0", "-1"}) {
            BotReply reply = handler.handle(CHAT, "/reminders 9:00 " + bad);

            assertTrue(reply.text().contains("Не понял время «" + bad + "»"), reply.text());
        }
        verify(reminders, never()).setTimes(anyLong(), any());
    }

    @Test
    void remindersRejectsTooManyTimes() {
        BotReply reply = handler.handle(CHAT, "/reminders 8 9 10 11 12 13 14");

        assertTrue(reply.text().contains("Слишком много: максимум 6"), reply.text());
        verify(reminders, never()).setTimes(anyLong(), any());
    }

    @Test
    void remindersDuplicateTimesAreNotCountedTwice() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(8, 0)));

        handler.handle(CHAT, "/reminders 8 8 8 8 8 8 8 8");

        verify(reminders).setTimes(CHAT, List.of(LocalTime.of(8, 0), LocalTime.of(8, 0), LocalTime.of(8, 0),
                LocalTime.of(8, 0), LocalTime.of(8, 0), LocalTime.of(8, 0), LocalTime.of(8, 0), LocalTime.of(8, 0)));
    }

    @Test
    void remindersOffOnDefaultCommands() {
        when(reminders.get(CHAT)).thenReturn(settings(false, false, LocalTime.of(13, 0)));

        assertTrue(handler.handle(CHAT, "/reminders off").text().startsWith("✅ Готово, напоминания выключены."));
        assertTrue(handler.handle(CHAT, "/reminders ВЫКЛ").text().startsWith("✅ Готово, напоминания выключены."));
        assertTrue(handler.handle(CHAT, "/reminders on").text().startsWith("✅ Готово, напоминания включены."));
        assertTrue(handler.handle(CHAT, "/reminders default").text().startsWith("✅ Готово, вернул стандартное"));

        verify(reminders, org.mockito.Mockito.times(2)).setEnabled(CHAT, false);
        verify(reminders).setEnabled(CHAT, true);
        verify(reminders).resetToDefault(CHAT);
    }

    @Test
    void reminderButtonsChangeSettingsAndRefreshView() {
        when(reminders.get(CHAT)).thenReturn(settings(false, false, LocalTime.of(13, 0)));

        CallbackResponse off = handler.handleCallback(CHAT, "rem:off");
        CallbackResponse on = handler.handleCallback(CHAT, "rem:on");
        CallbackResponse reset = handler.handleCallback(CHAT, "rem:default");

        verify(reminders).setEnabled(CHAT, false);
        verify(reminders).setEnabled(CHAT, true);
        verify(reminders).resetToDefault(CHAT);
        assertEquals("Напоминания выключены", off.notice());
        assertEquals("Напоминания включены", on.notice());
        assertEquals("Время сброшено", reset.notice());
        assertTrue(off.view().text().contains("Напоминания"));
    }

    @Test
    void remindersFromUnregisteredUserAsksToPressStart() {
        when(reminders.get(CHAT)).thenThrow(new UserNotRegisteredException(CHAT));

        assertEquals("👋 Сначала нажми /start.", handler.handle(CHAT, "/reminders").text());
        assertEquals("Сначала нажми /start", handler.handleCallback(CHAT, "rem:off").notice());
    }

    @Test
    void callbackFromUnregisteredUserAsksToPressStart() {
        when(service.markDone(CHAT, 1L)).thenThrow(new UserNotRegisteredException(CHAT));

        CallbackResponse response = handler.handleCallback(CHAT, "done:1");

        assertEquals("Сначала нажми /start", response.notice());
        assertNull(response.view());
    }
}
