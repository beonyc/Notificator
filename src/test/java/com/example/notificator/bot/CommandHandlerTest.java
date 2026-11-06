package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.notificator.service.MarkResult;
import com.example.notificator.stats.StatsService;
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

        assertTrue(handler.handle(CHAT, "/start").text().startsWith("Привет!"));
    }

    @Test
    void startForExistingUserSaysAlreadyRegistered() {
        when(service.register(CHAT)).thenReturn(false);

        assertTrue(handler.handle(CHAT, "/start").text().startsWith("Ты уже зарегистрирован"));
    }

    @Test
    void addSavesTaskWithFullTitle() {
        BotReply answer = handler.handle(CHAT, "/add  Читать 30 минут ");

        verify(service).addTask(CHAT, "Читать 30 минут");
        assertEquals("Добавлено: Читать 30 минут", answer.text());
    }

    @Test
    void addWithoutTitleAsksForTitle() {
        BotReply answer = handler.handle(CHAT, "/add");

        verify(service, never()).addTask(anyLong(), anyString());
        assertTrue(answer.text().startsWith("Напиши название дела"));
    }

    @Test
    void addRejectsTooLongTitle() {
        BotReply answer = handler.handle(CHAT, "/add " + "я".repeat(CommandHandler.MAX_TITLE_LENGTH + 1));

        verify(service, never()).addTask(anyLong(), anyString());
        assertTrue(answer.text().startsWith("Слишком длинное название"));
    }

    @Test
    void commandWithBotNameSuffixAndUpperCaseIsRecognized() {
        when(service.todayItems(CHAT)).thenReturn(List.of());

        assertTrue(handler.handle(CHAT, "/TODAY@NotificatorBot").text().startsWith("На сегодня дел нет"));
    }

    @Test
    void todayShowsListAndButtonOnlyForNotDoneTasks() {
        when(service.todayItems(CHAT)).thenReturn(List.of(
                new TodayItem(10, "Читать", true), new TodayItem(11, "Зарядка", false)));

        BotReply reply = handler.handle(CHAT, "/today");

        assertEquals("""
                Дела на сегодня:
                ✅ 1. Читать
                ⬜ 2. Зарядка

                Выполнено 1 из 2""", reply.text());
        assertEquals(List.of(new ReplyButton("✅ 2. Зарядка", "done:11")), reply.buttons());
    }

    @Test
    void todayWithAllDoneHasNoButtons() {
        when(service.todayItems(CHAT)).thenReturn(List.of(new TodayItem(10, "Читать", true)));

        BotReply reply = handler.handle(CHAT, "/today");

        assertTrue(reply.buttons().isEmpty());
        assertTrue(reply.text().endsWith("Все дела выполнены, день закрыт 💪"));
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

        assertEquals("Сначала нажми /start.", handler.handle(CHAT, "/today").text());
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
        assertTrue(response.view().text().contains("✅ 1. Зарядка"));
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
                Какое дело убрать из списка?
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
        assertTrue(response.view().text().startsWith("Удалить все дела (2)?"));
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
        assertTrue(response.view().text().startsWith("Дел для удаления нет"));
    }

    @Test
    void removeAllCancelledKeepsTasksAndShowsListAgain() {
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(7, "Читать"), new TaskItem(8, "Зарядка")));

        CallbackResponse response = handler.handleCallback(CHAT, "rmall:no");

        verify(service, never()).removeAllTasks(anyLong());
        assertEquals("Отменено", response.notice());
        assertTrue(response.view().text().startsWith("Какое дело убрать"));
    }

    @Test
    void removeWithNoTasksSaysNothingToRemove() {
        when(service.activeTasks(CHAT)).thenReturn(List.of());

        BotReply reply = handler.handle(CHAT, "/remove");

        assertTrue(reply.text().startsWith("Дел для удаления нет"));
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

        assertTrue(reply.text().startsWith("📊 Неделя в процессе 02.11–07.11"), reply.text());
        assertTrue(reply.buttons().isEmpty());
    }

    @Test
    void weekFromUnregisteredUserAsksToPressStart() {
        when(stats.currentWeek(CHAT)).thenThrow(new UserNotRegisteredException(CHAT));

        assertEquals("Сначала нажми /start.", handler.handle(CHAT, "/week").text());
    }

    // ---------- /reminders ----------

    private static ReminderSettings settings(boolean enabled, boolean custom, LocalTime... times) {
        return new ReminderSettings(enabled, List.of(times), custom, "Europe/Moscow");
    }

    @Test
    void remindersShowsDefaultScheduleWithOffButton() {
        when(reminders.get(CHAT)).thenReturn(settings(true, false, LocalTime.of(13, 0), LocalTime.of(17, 0)));

        BotReply reply = handler.handle(CHAT, "/reminders");

        assertTrue(reply.text().startsWith("🔔 Напоминания: 13:00, 17:00 (по умолчанию)"), reply.text());
        assertTrue(reply.text().contains("Europe/Moscow"));
        assertEquals(List.of(new ReplyButton("✏️ Изменить время", "rem:edit"),
                new ReplyButton("🔕 Выключить", "rem:off")), reply.buttons());
    }

    @Test
    void remindersShowsCustomScheduleWithResetButton() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 30)));

        BotReply reply = handler.handle(CHAT, "/reminders");

        assertTrue(reply.text().startsWith("🔔 Напоминания: 09:30\n"), reply.text());
        assertEquals(List.of(new ReplyButton("✏️ Изменить время", "rem:edit"),
                new ReplyButton("🔕 Выключить", "rem:off"),
                new ReplyButton("↩️ По умолчанию", "rem:default")), reply.buttons());
    }

    @Test
    void remindersWhenDisabledShowsOnButton() {
        when(reminders.get(CHAT)).thenReturn(settings(false, false, LocalTime.of(13, 0)));

        BotReply reply = handler.handle(CHAT, "/reminders");

        assertTrue(reply.text().startsWith("🔕 Напоминания выключены."), reply.text());
        assertTrue(reply.text().contains("Сохранённое время: 13:00"));
        assertEquals(List.of(new ReplyButton("✏️ Изменить время", "rem:edit"),
                new ReplyButton("🔔 Включить", "rem:on")), reply.buttons());
    }

    @Test
    void editButtonAsksForTimesAndNextPlainMessageSetsThem() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0), LocalTime.of(18, 0)));

        CallbackResponse ask = handler.handleCallback(CHAT, "rem:edit");

        assertEquals("Жду время", ask.notice());
        assertTrue(ask.view().text().startsWith("Отправь время напоминаний одним сообщением"), ask.view().text());
        assertTrue(ask.view().text().contains("Europe/Moscow"));
        assertEquals(List.of(new ReplyButton("↩️ Отмена", "rem:cancel")), ask.view().buttons());

        BotReply reply = handler.handle(CHAT, "9:00 18:00"); // обычное сообщение без команды

        verify(reminders).setTimes(CHAT, List.of(LocalTime.of(9, 0), LocalTime.of(18, 0)));
        assertTrue(reply.text().startsWith("Готово, время напоминаний обновлено."), reply.text());
    }

    @Test
    void afterTimesAreSetPlainTextIsNoLongerTreatedAsTimes() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));
        handler.handleCallback(CHAT, "rem:edit");
        handler.handle(CHAT, "9:00");

        BotReply reply = handler.handle(CHAT, "привет");

        assertTrue(reply.text().startsWith("Не понял. Команды"), reply.text());
        verify(reminders, org.mockito.Mockito.times(1)).setTimes(anyLong(), any());
    }

    @Test
    void invalidTimesWhileWaitingKeepWaitingAndMentionCancel() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));
        handler.handleCallback(CHAT, "rem:edit");

        BotReply bad = handler.handle(CHAT, "утром и вечером");

        assertTrue(bad.text().startsWith("Не понял время «утром»"), bad.text());
        assertTrue(bad.text().contains("/cancel"));
        verify(reminders, never()).setTimes(anyLong(), any());

        handler.handle(CHAT, "8:30"); // исправленный вариант принимается без повторного нажатия кнопки
        verify(reminders).setTimes(CHAT, List.of(LocalTime.of(8, 30)));
    }

    @Test
    void cancelCommandAndCancelButtonStopWaiting() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));

        handler.handleCallback(CHAT, "rem:edit");
        assertEquals("Отменено.", handler.handle(CHAT, "/cancel").text());
        assertTrue(handler.handle(CHAT, "9:00").text().startsWith("Не понял. Команды"));

        handler.handleCallback(CHAT, "rem:edit");
        CallbackResponse cancelled = handler.handleCallback(CHAT, "rem:cancel");
        assertEquals("Отменено", cancelled.notice());
        assertTrue(cancelled.view().text().contains("Напоминания"));
        assertTrue(handler.handle(CHAT, "9:00").text().startsWith("Не понял. Команды"));

        assertEquals("Отменять нечего.", handler.handle(CHAT, "/cancel").text());
        verify(reminders, never()).setTimes(anyLong(), any());
    }

    @Test
    void anotherCommandWhileWaitingWorksAndStopsWaiting() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));
        when(service.todayItems(CHAT)).thenReturn(List.of());
        handler.handleCallback(CHAT, "rem:edit");

        BotReply today = handler.handle(CHAT, "/today");

        assertTrue(today.text().startsWith("На сегодня дел нет"), today.text());
        assertTrue(handler.handle(CHAT, "9:00").text().startsWith("Не понял. Команды")); // ожидание сброшено
    }

    @Test
    void plainTextWithoutPressingEditButtonIsNotTreatedAsTimes() {
        BotReply reply = handler.handle(CHAT, "9:00 18:00");

        assertTrue(reply.text().startsWith("Не понял. Команды"), reply.text());
        verify(reminders, never()).setTimes(anyLong(), any());
    }

    @Test
    void editButtonFromUnregisteredUserAsksToPressStart() {
        when(reminders.get(CHAT)).thenThrow(new UserNotRegisteredException(CHAT));

        assertEquals("Сначала нажми /start", handler.handleCallback(CHAT, "rem:edit").notice());
        assertTrue(handler.handle(CHAT, "9:00").text().startsWith("Не понял. Команды")); // ожидание не включилось
    }

    @Test
    void remindersSetsCustomTimesInDifferentFormats() {
        when(reminders.get(CHAT)).thenReturn(settings(true, true, LocalTime.of(9, 0)));

        BotReply reply = handler.handle(CHAT, "/reminders 9 14:30,20.15  7:05");

        verify(reminders).setTimes(CHAT, List.of(LocalTime.of(9, 0), LocalTime.of(14, 30),
                LocalTime.of(20, 15), LocalTime.of(7, 5)));
        assertTrue(reply.text().startsWith("Готово, время напоминаний обновлено."), reply.text());
    }

    @Test
    void remindersRejectsInvalidTimeWithoutChangingAnything() {
        for (String bad : new String[] {"25:00", "9:60", "abc", "9:0", "-1"}) {
            BotReply reply = handler.handle(CHAT, "/reminders 9:00 " + bad);

            assertTrue(reply.text().startsWith("Не понял время «" + bad + "»"), reply.text());
        }
        verify(reminders, never()).setTimes(anyLong(), any());
    }

    @Test
    void remindersRejectsTooManyTimes() {
        BotReply reply = handler.handle(CHAT, "/reminders 8 9 10 11 12 13 14");

        assertTrue(reply.text().startsWith("Слишком много: максимум 6"), reply.text());
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

        assertTrue(handler.handle(CHAT, "/reminders off").text().startsWith("Готово, напоминания выключены."));
        assertTrue(handler.handle(CHAT, "/reminders ВЫКЛ").text().startsWith("Готово, напоминания выключены."));
        assertTrue(handler.handle(CHAT, "/reminders on").text().startsWith("Готово, напоминания включены."));
        assertTrue(handler.handle(CHAT, "/reminders default").text().startsWith("Готово, вернул стандартное"));

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

        assertEquals("Сначала нажми /start.", handler.handle(CHAT, "/reminders").text());
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
