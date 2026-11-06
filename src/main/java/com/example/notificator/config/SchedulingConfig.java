package com.example.notificator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает планировщик: без {@code @EnableScheduling} методы с {@code @Scheduled} не запускаются.
 * Как и бот, отключается настройкой telegram.enabled=false (в тестах).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
