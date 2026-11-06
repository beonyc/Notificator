package com.example.notificator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Включает чтение настроек уведомлений ({@link NotificationProperties}). */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {
}
