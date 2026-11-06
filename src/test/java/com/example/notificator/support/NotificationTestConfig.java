package com.example.notificator.support;

import com.example.notificator.config.NotificationProperties;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Общие тестовые бины для тестов уведомлений и цитат: управляемые часы (по умолчанию
 * 2026-11-07 05:00 UTC = 08:00 в Москве), настройки как в application.yaml и генератор
 * случайных чисел с фиксированным зерном.
 */
@TestConfiguration
public class NotificationTestConfig {

    @Bean
    public MutableClock clock() {
        return new MutableClock(Instant.parse("2026-11-07T05:00:00Z"));
    }

    @Bean
    public NotificationProperties notificationProperties() {
        return new NotificationProperties(LocalTime.of(8, 0),
                List.of(LocalTime.of(13, 0), LocalTime.of(17, 0), LocalTime.of(21, 0)), 30, 7);
    }

    @Bean
    public RandomGenerator randomGenerator() {
        return new Random(7);
    }
}
