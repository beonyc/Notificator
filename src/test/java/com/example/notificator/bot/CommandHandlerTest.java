package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Юнит-тесты разбора команд. Сервис — мок, БД и Telegram не нужны. */
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

        assertTrue(handler.handle(CHAT, "/start").startsWith("Привет!"));
    }

    @Test
    void startForExistingUserSaysAlreadyRegistered() {
        when(service.register(CHAT)).thenReturn(false);

        assertTrue(handler.handle(CHAT, "/start").startsWith("Ты уже зарегистрирован"));
    }

    @Test
    void addSavesTaskWithFullTitle() {
        String answer = handler.handle(CHAT, "/add  Читать 30 минут ");

        verify(service).addTask(CHAT, "Читать 30 минут");
        assertEquals("Добавлено: Читать 30 минут", answer);
    }

    @Test
    void addWithoutTitleAsksForTitle() {
        String answer = handler.handle(CHAT, "/add");

        verify(service, never()).addTask(anyLong(), anyString());
        assertTrue(answer.startsWith("Напиши название дела"));
    }

    @Test
    void addRejectsTooLongTitle() {
        String answer = handler.handle(CHAT, "/add " + "я".repeat(CommandHandler.MAX_TITLE_LENGTH + 1));

        verify(service, never()).addTask(anyLong(), anyString());
        assertTrue(answer.startsWith("Слишком длинное название"));
    }

    @Test
    void commandWithBotNameSuffixAndUpperCaseIsRecognized() {
        when(service.todayItems(CHAT)).thenReturn(List.of());

        assertTrue(handler.handle(CHAT, "/TODAY@NotificatorBot").startsWith("На сегодня дел нет"));
    }

    @Test
    void todayShowsListWithStatusesAndCounter() {
        when(service.todayItems(CHAT)).thenReturn(List.of(
                new TodayItem("Читать", true), new TodayItem("Зарядка", false)));

        assertEquals("""
                Дела на сегодня:
                ✅ 1. Читать
                ⬜ 2. Зарядка

                Выполнено 1 из 2""", handler.handle(CHAT, "/today"));
    }

    @Test
    void unregisteredUserIsAskedToPressStart() {
        when(service.todayItems(CHAT)).thenThrow(new UserNotRegisteredException(CHAT));

        assertEquals("Сначала нажми /start.", handler.handle(CHAT, "/today"));
    }

    @Test
    void unknownTextShowsHelp() {
        assertTrue(handler.handle(CHAT, "привет").contains("/today"));
    }
}
