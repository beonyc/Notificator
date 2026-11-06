package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
 * Юнит-тесты эхо-бота. Настоящий Telegram не нужен: клиент заменён на мок (Mockito) —
 * «пустышку», у которой можно проверить, какие методы и с какими аргументами вызвали.
 */
class EchoBotTest {

    private TelegramClient client;
    private EchoBot bot;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        bot = new EchoBot("test-token", client);
    }

    @Test
    void repliesWithSameTextToSameChat() throws TelegramApiException {
        bot.consume(updateWithText(12345L, "Привет"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertEquals("Привет", captor.getValue().getText());
        assertEquals("12345", captor.getValue().getChatId());
    }

    @Test
    void ignoresUpdateWithoutText() throws TelegramApiException {
        bot.consume(new Update());

        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    void returnsTokenForStarter() {
        assertEquals("test-token", bot.getBotToken());
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
