package com.example.notificator.bot;

import com.example.notificator.notification.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/** Отправляет уведомления через Telegram Bot API. */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final TelegramClient telegramClient;

    public TelegramNotifier(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public boolean send(long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
            return true;
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить уведомление в чат {}", chatId, e);
            return false;
        }
    }
}
