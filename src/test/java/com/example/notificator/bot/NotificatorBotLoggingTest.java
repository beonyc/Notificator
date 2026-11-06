package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.notificator.support.LogCapture;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Проверяет, что бот пишет в лог каждый шаг: что пришло от пользователя («→») и что бот ответил или сделал («←»),
 * а также что номер чата кладётся в MDC на время обработки и убирается после неё.
 */
class NotificatorBotLoggingTest {

    private static final long CHAT = 12345L;

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
    void incomingMessageAndReplyAreLogged() {
        when(handler.handle(CHAT, "/today")).thenReturn(
                new BotReply("Список дел", List.of(new ReplyButton("✅ 1. Читать", "done:5"))));

        try (LogCapture log = new LogCapture(NotificatorBot.class)) {
            bot.consume(updateWithText("/today"));

            assertTrue(log.contains("→ сообщение: /today"), log.messages().toString());
            ILoggingEvent reply = log.find("← ответ");
            assertTrue(reply.getFormattedMessage().contains("кнопок: 1"), reply.getFormattedMessage());
            assertTrue(reply.getFormattedMessage().contains("Список дел"), reply.getFormattedMessage());
        }
    }

    @Test
    void multilineReplyIsWrittenAsSingleLogLine() {
        when(handler.handle(CHAT, "/today")).thenReturn(BotReply.text("Строка 1\nСтрока 2\n\nСтрока 3"));

        try (LogCapture log = new LogCapture(NotificatorBot.class)) {
            bot.consume(updateWithText("/today"));

            String message = log.find("← ответ").getFormattedMessage();
            assertFalse(message.contains("\n"), message);
            assertTrue(message.contains("Строка 1 ⏎ Строка 2 ⏎ Строка 3"), message);
        }
    }

    @Test
    void chatIdIsInMdcWhileProcessingAndRemovedAfterwards() {
        List<String> seenByHandler = new ArrayList<>();
        when(handler.handle(CHAT, "/today")).thenAnswer(invocation -> {
            seenByHandler.add(MDC.get("chatId")); // так видит номер чата любой код под обработчиком (например, сервисы)
            return BotReply.text("ok");
        });

        try (LogCapture log = new LogCapture(NotificatorBot.class)) {
            bot.consume(updateWithText("/today"));

            assertEquals(List.of("12345"), seenByHandler);
            assertEquals("12345", log.find("→ сообщение").getMDCPropertyMap().get("chatId"));
            assertEquals("12345", log.find("← ответ").getMDCPropertyMap().get("chatId"));
        }
        assertNull(MDC.get("chatId"), "после обработки номер чата должен быть убран из MDC");
    }

    @Test
    void mdcIsCleanedEvenWhenHandlerFails() {
        when(handler.handle(CHAT, "/today")).thenThrow(new IllegalStateException("БД недоступна"));

        bot.consume(updateWithText("/today"));

        assertNull(MDC.get("chatId"));
    }

    @Test
    void handlerFailureIsLoggedAsErrorWithException() {
        when(handler.handle(CHAT, "/today")).thenThrow(new IllegalStateException("БД недоступна"));

        try (LogCapture log = new LogCapture(NotificatorBot.class)) {
            bot.consume(updateWithText("/today"));

            ILoggingEvent error = log.find("Ошибка при обработке сообщения");
            assertEquals(Level.ERROR, error.getLevel());
            assertNotNull(error.getThrowableProxy());
            assertEquals("БД недоступна", error.getThrowableProxy().getMessage());
            assertTrue(log.contains("← ответ"), "пользователю всё равно уходит ответ об ошибке");
        }
    }

    @Test
    void sendFailureIsLoggedAsError() throws TelegramApiException {
        when(handler.handle(CHAT, "/today")).thenReturn(BotReply.text("ok"));
        doThrow(new TelegramApiException("нет сети")).when(client).execute(any(SendMessage.class));

        try (LogCapture log = new LogCapture(NotificatorBot.class)) {
            bot.consume(updateWithText("/today"));

            assertEquals(Level.ERROR, log.find("Не удалось отправить ответ").getLevel());
            assertFalse(log.contains("← ответ ("), "успешный ответ в лог попадать не должен");
        }
    }

    @Test
    void buttonPressAndBotReactionAreLogged() {
        when(handler.handleCallback(CHAT, "done:5")).thenReturn(
                new CallbackResponse("Выполнено ✅", new BotReply("Новый текст", List.of())));

        try (LogCapture log = new LogCapture(NotificatorBot.class)) {
            bot.consume(updateWithCallback(99, "cb-1", "done:5"));

            assertTrue(log.contains("→ нажата кнопка: done:5 (сообщение 99)"), log.messages().toString());
            assertTrue(log.contains("← всплывающее уведомление: Выполнено ✅"), log.messages().toString());
            assertTrue(log.contains("← сообщение 99 обновлено (кнопок: 0): Новый текст"), log.messages().toString());
            assertEquals("12345", log.find("→ нажата кнопка").getMDCPropertyMap().get("chatId"));
        }
        assertNull(MDC.get("chatId"));
    }

    @Test
    void buttonPressWithoutNoticeAndViewIsLoggedAsSuch() {
        when(handler.handleCallback(CHAT, "x")).thenReturn(new CallbackResponse(null, null));

        try (LogCapture log = new LogCapture(NotificatorBot.class)) {
            bot.consume(updateWithCallback(99, "cb-2", "x"));

            assertTrue(log.contains("← всплывающее уведомление: нет"), log.messages().toString());
            assertFalse(log.contains("обновлено"), log.messages().toString());
        }
    }

    @Test
    void unsupportedUpdatesAreLoggedAsSkipped() {
        Message sticker = new Message(); // сообщение без текста
        sticker.setChat(Chat.builder().id(CHAT).type("private").build());
        Update stickerUpdate = new Update();
        stickerUpdate.setMessage(sticker);

        try (LogCapture log = new LogCapture(NotificatorBot.class)) {
            bot.consume(stickerUpdate);
            bot.consume(new Update());

            assertTrue(log.contains("→ пропущено: сообщение без текста"), log.messages().toString());
            assertTrue(log.contains("→ пропущено: обновление неподдерживаемого типа"), log.messages().toString());
        }
    }

    private static Update updateWithText(String text) {
        Message message = new Message();
        message.setText(text);
        message.setChat(Chat.builder().id(CHAT).type("private").build());
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private static Update updateWithCallback(int messageId, String callbackId, String data) {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setChat(Chat.builder().id(CHAT).type("private").build());
        CallbackQuery callback = new CallbackQuery();
        callback.setId(callbackId);
        callback.setData(data);
        callback.setMessage(message);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }
}
