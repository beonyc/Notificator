package com.example.notificator.bot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Конфигурация Telegram: создаёт клиент, через который бот отправляет сообщения.
 *
 * {@code @Configuration} — говорит Spring, что в классе описаны бины (объекты, которыми
 * Spring управляет сам и подставляет их туда, где они нужны).
 *
 * {@code @ConditionalOnProperty} — конфигурация включается, только если telegram.enabled=true
 * (по умолчанию true). В тестах мы ставим false, чтобы приложение не подключалось к Telegram.
 */
@Configuration
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramConfig {

    /**
     * Клиент Telegram Bot API. Токен берётся из application.yaml (telegram.botToken),
     * а туда он попадает из переменной окружения TELEGRAM_BOT_TOKEN.
     * {@code @Value} читает значение настройки и передаёт его в параметр метода.
     */
    @Bean
    public TelegramClient telegramClient(@Value("${telegram.botToken}") String botToken) {
        return new OkHttpTelegramClient(botToken);
    }
}
