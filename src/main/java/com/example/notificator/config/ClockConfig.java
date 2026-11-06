package com.example.notificator.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Часы как бин. Сервисы берут «текущее время» у Clock, а не вызывают LocalDate.now() напрямую:
 * в тестах можно подставить фиксированные часы и проверять логику, зависящую от даты и часового пояса.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Источник случайных чисел как бин: в тестах его подменяют на предсказуемый. */
    @Bean
    public RandomGenerator randomGenerator() {
        return new SecureRandom();
    }
}
