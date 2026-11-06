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
 * Telegram-бот трекера целей. Принимает сообщения из Telegram, передаёт текст в
 * {@link CommandHandler} и отправляет полученный ответ обратно в чат.
 * (Раньше это был эхо-бот; теперь вместо повтора текста выполняются команды.)
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
public class NotificatorBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificatorBot.class);

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
     * Вызывается на каждое новое обновление. Если пришло текстовое сообщение, выполняем его
     * как команду и отправляем ответ. Всё остальное (стикеры, фото, нажатия кнопок)
     * пока игнорируем.
     */
    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();

            String answer;
            try {
                answer = commandHandler.handle(chatId, update.getMessage().getText());
            } catch (RuntimeException e) {
                // Неожиданная ошибка (например, БД недоступна): сообщаем пользователю и
                // логируем, но не роняем бота.
                log.error("Ошибка при обработке сообщения из чата {}", chatId, e);
                answer = "Что-то пошло не так, попробуй ещё раз позже.";
            }

            SendMessage reply = SendMessage.builder()
                    .chatId(chatId)
                    .text(answer)
                    .build();
            try {
                telegramClient.execute(reply);
                log.info(reply.toString());
            } catch (TelegramApiException e) {
                // Ошибку логируем и не пробрасываем: одно неудавшееся сообщение
                // не должно останавливать работу бота.
                log.error("Не удалось отправить ответ в чат {}", chatId, e);
            }
        }
    }
}
