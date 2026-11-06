package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
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
    void sendsHandlerAnswerWithButtonsToSameChat() throws TelegramApiException {
        when(handler.handle(12345L, "/today")).thenReturn(
                new BotReply("Ответ", List.of(new ReplyButton("✅ 1. Читать", "done:5"))));

        bot.consume(updateWithText(12345L, "/today"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        SendMessage sent = captor.getValue();
        assertEquals("Ответ", sent.getText());
        assertEquals("HTML", sent.getParseMode()); // тексты содержат теги <b>, <i>, <s>: нужен parse_mode=HTML
        assertEquals("12345", sent.getChatId());
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertEquals("done:5", markup.getKeyboard().get(0).get(0).getCallbackData());
        assertEquals("✅ 1. Читать", markup.getKeyboard().get(0).get(0).getText());
    }

    @Test
    void ignoresUpdateWithoutTextAndCallback() throws TelegramApiException {
        bot.consume(new Update());

        verify(client, never()).execute(any(SendMessage.class));
        verify(client, never()).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    void tellsUserWhenHandlerFails() throws TelegramApiException {
        when(handler.handle(1L, "/today")).thenThrow(new IllegalStateException("БД недоступна"));

        bot.consume(updateWithText(1L, "/today"));

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertEquals(NotificatorBot.ERROR_TEXT, captor.getValue().getText());
    }

    @Test
    void buttonPressAnswersCallbackAndEditsMessage() throws TelegramApiException {
        when(handler.handleCallback(7L, "done:5")).thenReturn(new CallbackResponse(
                "Выполнено ✅", new BotReply("Новый текст", List.of())));

        bot.consume(updateWithCallback(7L, 99, "cb-1", "done:5"));

        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(client).execute(answer.capture());
        assertEquals("cb-1", answer.getValue().getCallbackQueryId());
        assertEquals("Выполнено ✅", answer.getValue().getText());

        ArgumentCaptor<EditMessageText> edit = ArgumentCaptor.forClass(EditMessageText.class);
        verify(client).execute(edit.capture());
        assertEquals("7", edit.getValue().getChatId());
        assertEquals(99, edit.getValue().getMessageId());
        assertEquals("Новый текст", edit.getValue().getText());
        assertEquals("HTML", edit.getValue().getParseMode());
        assertTrue(edit.getValue().getReplyMarkup().getKeyboard().isEmpty());
    }

    @Test
    void buttonPressWithoutNewViewOnlyAnswersCallback() throws TelegramApiException {
        when(handler.handleCallback(7L, "x")).thenReturn(new CallbackResponse("Неизвестное действие", null));

        bot.consume(updateWithCallback(7L, 99, "cb-2", "x"));

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(client, never()).execute(any(EditMessageText.class));
    }

    @Test
    void buttonPressStillAnswersCallbackWhenHandlerFails() throws TelegramApiException {
        when(handler.handleCallback(7L, "done:5")).thenThrow(new IllegalStateException("БД недоступна"));

        bot.consume(updateWithCallback(7L, 99, "cb-3", "done:5"));

        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(client).execute(answer.capture());
        assertEquals(NotificatorBot.ERROR_TEXT, answer.getValue().getText());
        verify(client, never()).execute(any(EditMessageText.class));
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

    /** Собирает входящее обновление «нажата inline-кнопка под сообщением». */
    private static Update updateWithCallback(long chatId, int messageId, String callbackId, String data) {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setChat(Chat.builder().id(chatId).type("private").build());
        CallbackQuery callback = new CallbackQuery();
        callback.setId(callbackId);
        callback.setData(data);
        callback.setMessage(message);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }
}
