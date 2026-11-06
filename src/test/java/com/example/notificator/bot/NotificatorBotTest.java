package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Юнит-тесты бота. Telegram и обработчик команд заменены моками (Mockito) —
 * «пустышками», у которых можно задать ответы и проверить вызовы.
 */
class NotificatorBotTest {

    private TelegramClient client;
    private CommandHandler handler;
    private NotificatorBot bot;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        handler = mock(CommandHandler.class);
        bot = new NotificatorBot("test-token", client, handler);
    }

    @Test
    void sendsHandlerAnswerToSameChat() throws TelegramApiException {
        when(handler.handle(12345L, "/today")).thenReturn("Ответ");

        bot.consume(updateWithText(12345L, "/today"));

        SendMessage sent = captureSent();
        assertEquals("Ответ", sent.getText());
        assertEquals("12345", sent.getChatId());
    }

    @Test
    void ignoresUpdateWithoutText() throws TelegramApiException {
        bot.consume(new Update());

        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    void tellsUserWhenHandlerFails() throws TelegramApiException {
        when(handler.handle(1L, "/today")).thenThrow(new IllegalStateException("БД недоступна"));

        bot.consume(updateWithText(1L, "/today"));

        assertEquals("Что-то пошло не так, попробуй ещё раз позже.", captureSent().getText());
    }

    @Test
    void returnsTokenForStarter() {
        assertEquals("test-token", bot.getBotToken());
    }

    private SendMessage captureSent() throws TelegramApiException {
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        return captor.getValue();
    }

    /** Собирает входящее обновление с текстовым сообщением. */
    private static Update updateWithText(long chatId, String text) {
        Message message = new Message();
        message.setText(text);
        message.setChat(Chat.builder().id(chatId).type("private").build());
        Update update = new Update();
        update.setMessage(message);
        return update;
    }
}
