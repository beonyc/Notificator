package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.example.notificator.service.ReminderSettings;
import com.example.notificator.service.ReminderSettingsService;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import com.example.notificator.stats.StatsService;
import com.example.notificator.support.LogCapture;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Проверяет шаги, которые обработчик команд пишет в лог: распознанные команды, непонятный ввод, подозрительные кнопки. */
class CommandHandlerLoggingTest {

    private static final long CHAT = 42L;

    private TrackerService service;
    private ReminderSettingsService reminders;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(TrackerService.class);
        reminders = mock(ReminderSettingsService.class);
        when(reminders.get(CHAT)).thenReturn(
                new ReminderSettings(true, List.of(LocalTime.of(13, 0)), false, "Europe/Moscow"));
        handler = new CommandHandler(service, reminders, mock(StatsService.class));
    }

    @Test
    void recognizedCommandIsLoggedWithArguments() {
        try (LogCapture log = new LogCapture(CommandHandler.class)) {
            handler.handle(CHAT, "/add Читать 30 минут");
            handler.handle(CHAT, "/today@MyBot");

            assertTrue(log.contains("Команда /add с аргументами «Читать 30 минут»"), log.messages().toString());
            assertTrue(log.contains("Команда /today"), log.messages().toString());
            assertFalse(log.find("Команда /today").getFormattedMessage().contains("аргументами"));
        }
    }

    @Test
    void plainTextIsLoggedAsNotUnderstood() {
        try (LogCapture log = new LogCapture(CommandHandler.class)) {
            handler.handle(CHAT, "привет");

            assertTrue(log.contains("Не понял ввод"), log.messages().toString());
            assertFalse(log.contains("Команда "), "обычный текст не должен логироваться как команда");
        }
    }

    @Test
    void unregisteredUserIsLogged() {
        when(service.todayItems(anyLong())).thenThrow(new UserNotRegisteredException(CHAT));

        try (LogCapture log = new LogCapture(CommandHandler.class)) {
            handler.handle(CHAT, "/today");
            handler.handleCallback(CHAT, "doneall");

            assertTrue(log.contains("Пользователь не зарегистрирован: прошу нажать /start"), log.messages().toString());
            assertTrue(log.contains("Кнопка нажата незарегистрированным пользователем"), log.messages().toString());
        }
    }

    @Test
    void unknownOrForgedButtonDataIsLoggedAsWarning() {
        try (LogCapture log = new LogCapture(CommandHandler.class)) {
            handler.handleCallback(CHAT, "hack");
            handler.handleCallback(CHAT, "done:abc");
            handler.handleCallback(CHAT, null);

            List<String> warnings = log.events().stream()
                    .filter(e -> e.getLevel() == Level.WARN).map(e -> e.getFormattedMessage()).toList();
            assertEquals(List.of(
                    "Неизвестные или некорректные данные кнопки: hack",
                    "Неизвестные или некорректные данные кнопки: done:abc",
                    "Неизвестные или некорректные данные кнопки: null"), warnings);
        }
    }

    @Test
    void validButtonIsNotLoggedAsWarning() {
        when(service.markAllDone(CHAT)).thenReturn(1);

        try (LogCapture log = new LogCapture(CommandHandler.class)) {
            handler.handleCallback(CHAT, "doneall");

            assertTrue(log.events().stream().noneMatch(e -> e.getLevel() == Level.WARN), log.messages().toString());
        }
    }

    @Test
    void waitingForReminderTimesIsLoggedThroughItsWholeCycle() {
        try (LogCapture log = new LogCapture(CommandHandler.class)) {
            handler.handleCallback(CHAT, "rem:edit");
            handler.handle(CHAT, "9:00 18:00");
            handler.handleCallback(CHAT, "rem:edit");
            handler.handle(CHAT, "/cancel");
            handler.handleCallback(CHAT, "rem:edit");
            handler.handle(CHAT, "/today");

            assertEquals(3, log.messages().stream().filter(m -> m.contains("Жду от пользователя время")).count());
            assertTrue(log.contains("разбираю ответ как список времён"), log.messages().toString());
            assertTrue(log.contains("ожидание времени напоминаний прекращено"), log.messages().toString());
            assertTrue(log.contains("Ожидание времени напоминаний сброшено: пришла другая команда /today"),
                    log.messages().toString());
        }
    }

    @Test
    void cancelWithNothingToCancelIsLogged() {
        try (LogCapture log = new LogCapture(CommandHandler.class)) {
            handler.handle(CHAT, "/cancel");

            assertTrue(log.contains("отменять было нечего"), log.messages().toString());
        }
    }
}
