package com.example.notificator.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Эхо-бот: отвечает пользователю тем же текстом, который получил.
 * Нужен, чтобы проверить, что связь с Telegram работает.
 *
 * Как это устроено:
 * - Long polling: приложение само периодически спрашивает у Telegram «есть новые сообщения?».
 *   Не нужен публичный адрес и HTTPS, поэтому подходит для запуска на своём компьютере.
 * - {@link SpringLongPollingBot} — стартер при запуске находит бины с этим интерфейсом
 *   и сам регистрирует их в Telegram (нам не нужно делать это вручную).
 * - {@link LongPollingSingleThreadUpdateConsumer} — обработчик входящих обновлений (Update);
 *   «SingleThread» значит, что обновления обрабатываются по одному, в одном потоке.
 *   Так проще и безопаснее для начала.
 *
 * {@code @Component} — Spring создаёт один экземпляр класса и управляет им.
 */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true", matchIfMissing = true)
public class EchoBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(EchoBot.class);

    private final String botToken;
    private final TelegramClient telegramClient;

    /**
     * Зависимости передаются через конструктор (внедрение зависимостей, DI): Spring сам
     * подставит TelegramClient из {@link TelegramConfig}, а токен — из настроек.
     * Так класс легко тестировать: в тесте можно передать «поддельный» клиент.
     */
    public EchoBot(@Value("${telegram.botToken}") String botToken, TelegramClient telegramClient) {
        this.botToken = botToken;
        this.telegramClient = telegramClient;
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
     * Вызывается на каждое новое обновление. Если пришло текстовое сообщение,
     * отправляем в тот же чат тот же текст. Всё остальное (стикеры, фото, нажатия кнопок)
     * пока игнорируем.
     */
    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText();

            SendMessage reply = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build();
            try {
                telegramClient.execute(reply);
            } catch (TelegramApiException e) {
                // Ошибку логируем и не пробрасываем: одно неудавшееся сообщение
                // не должно останавливать работу бота.
                log.error("Не удалось отправить ответ в чат {}", chatId, e);
            }
        }
    }
}
