package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.notificator.service.MarkResult;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Юнит-тесты разбора команд и нажатий кнопок. Сервис — мок, БД и Telegram не нужны. */
class CommandHandlerTest {

    private static final long CHAT = 42L;

    private TrackerService service;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(TrackerService.class);
        handler = new CommandHandler(service);
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
    void callbackFromUnregisteredUserAsksToPressStart() {
        when(service.markDone(CHAT, 1L)).thenThrow(new UserNotRegisteredException(CHAT));

        CallbackResponse response = handler.handleCallback(CHAT, "done:1");

        assertEquals("Сначала нажми /start", response.notice());
        assertNull(response.view());
    }
}
