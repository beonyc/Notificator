package com.example.notificator.bot;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Telegram-бот трекера целей. Принимает сообщения и нажатия кнопок из Telegram, передаёт их в
 * {@link CommandHandler} и отправляет полученный ответ обратно в чат.
 *
 * Как это устроено:
 * - Long polling: приложение само периодически спрашивает у Telegram «есть новые сообщения?».
 *   Не нужен публичный адрес и HTTPS, поэтому подходит для запуска на своём компьютере.
 * - {@link SpringLongPollingBot} — стартер при запуске находит бины с этим интерфейсом
 *   и сам регистрирует их в Telegram (нам не нужно делать это вручную).
 * - {@link LongPollingSingleThreadUpdateConsumer} — обработчик входящих обновлений (Update);
 *   «SingleThread» значит, что обновления обрабатываются по одному, в одном потоке.
 *   Так проще и безопаснее для начала.
 * - Inline-кнопки: кнопки под сообщением. При нажатии Telegram присылает Update с CallbackQuery,
 *   на него нужно ответить (answerCallbackQuery), иначе у кнопки будут «крутиться часики».
 *
 * {@code @Component} — Spring создаёт один экземпляр класса и управляет им.
 */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true", matchIfMissing = true)
public class NotificatorBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificatorBot.class);

    static final String ERROR_TEXT = "Что-то пошло не так, попробуй ещё раз позже.";

    private final String botToken;
    private final TelegramClient telegramClient;
    private final CommandHandler commandHandler;

    /**
     * Зависимости передаются через конструктор (внедрение зависимостей, DI): Spring сам
     * подставит TelegramClient из {@link TelegramConfig}, обработчик команд и токен из настроек.
     * Так класс легко тестировать: в тесте можно передать «поддельные» зависимости.
     */
    public NotificatorBot(@Value("${telegram.botToken}") String botToken,
                          TelegramClient telegramClient,
                          CommandHandler commandHandler) {
        this.botToken = botToken;
        this.telegramClient = telegramClient;
        this.commandHandler = commandHandler;
    }

    /** Токен нужен стартеру, чтобы подключиться к Telegram. */
    @Override
    public String getBotToken() {
        return botToken;
    }

    /** Стартер будет передавать новые сообщения в этот объект (он сам и есть обработчик). */
    @Override
    public LongPollingSingleThreadUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    /**
     * Вызывается на каждое новое обновление: текстовое сообщение выполняем как команду,
     * нажатие кнопки передаём в {@link #onCallback}. Остальное (стикеры, фото) игнорируем.
     */
    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            onMessage(update.getMessage().getChatId(), update.getMessage().getText());
        } else if (update.hasCallbackQuery()) {
            onCallback(update.getCallbackQuery());
        }
    }

    private void onMessage(long chatId, String text) {
        BotReply reply;
        try {
            reply = commandHandler.handle(chatId, text);
        } catch (RuntimeException e) {
            // Неожиданная ошибка (например, БД недоступна): сообщаем пользователю и
            // логируем, но не роняем бота.
            log.error("Ошибка при обработке сообщения из чата {}", chatId, e);
            reply = BotReply.text(ERROR_TEXT);
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(reply.text())
                .replyMarkup(toMarkup(reply.buttons()))
                .build();
        try {
            telegramClient.execute(message);
            log.info(message.toString());
        } catch (TelegramApiException e) {
            // Ошибку логируем и не пробрасываем: одно неудавшееся сообщение
            // не должно останавливать работу бота.
            log.error("Не удалось отправить ответ в чат {}", chatId, e);
        }
    }

    /**
     * Нажатие кнопки: показываем всплывающее уведомление и обновляем текст сообщения с кнопками
     * (editMessageText), чтобы выполненное дело сразу получило ✅ и потеряло кнопку.
     */
    private void onCallback(CallbackQuery callback) {
        if (callback.getMessage() == null) {
            return; // кнопка из inline-режима — мы такие не создаём
        }
        long chatId = callback.getMessage().getChatId();
        int messageId = callback.getMessage().getMessageId();

        CallbackResponse response;
        try {
            response = commandHandler.handleCallback(chatId, callback.getData());
        } catch (RuntimeException e) {
            log.error("Ошибка при обработке нажатия кнопки в чате {}", chatId, e);
            response = new CallbackResponse(ERROR_TEXT, null);
        }

        try {
            // Отвечать на callback нужно всегда, иначе у кнопки в клиенте крутятся «часики».
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callback.getId())
                    .text(response.notice())
                    .build());
        } catch (TelegramApiException e) {
            log.error("Не удалось ответить на нажатие кнопки в чате {}", chatId, e);
        }

        if (response.view() != null) {
            try {
                telegramClient.execute(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(response.view().text())
                        .replyMarkup(toMarkup(response.view().buttons()))
                        .build());
            } catch (TelegramApiException e) {
                // Частый безобидный случай: «message is not modified» (текст не изменился).
                log.warn("Не удалось обновить сообщение {} в чате {}: {}", messageId, chatId, e.getMessage());
            }
        }
    }

    /**
     * Собирает клавиатуру Telegram из наших кнопок (по одной в строке).
     * Для пустого списка возвращает пустую клавиатуру: при редактировании сообщения
     * это убирает кнопки, а в новом сообщении ничего не показывает.
     */
    private static InlineKeyboardMarkup toMarkup(List<ReplyButton> buttons) {
        List<InlineKeyboardRow> rows = buttons.stream()
                .map(b -> new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text(b.text())
                        .callbackData(b.callbackData())
                        .build()))
                .toList();
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
