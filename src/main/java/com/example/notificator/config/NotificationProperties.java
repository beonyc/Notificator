package com.example.notificator.config;

import java.time.LocalTime;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки уведомлений из секции «notifications» файла application.yaml.
 * {@code @ConfigurationProperties} связывает настройки с полями record-класса, а {@code @DefaultValue}
 * задаёт значения, если настройка не указана. Все времена — по часовому поясу пользователя.
 *
 * @param summaryTime      время утреннего итога вчерашнего дня
 * @param reminderTimes    времена напоминаний о невыполненных делах
 * @param catchUpMinutes   сколько минут после назначенного времени уведомление ещё можно отправить
 *                         (нужно, если бот был выключен или Telegram не ответил)
 * @param quoteRepeatDays  не повторять одну цитату чаще, чем раз в столько дней
 */
@ConfigurationProperties("notifications")
public record NotificationProperties(
        @DefaultValue("08:00") LocalTime summaryTime,
        @DefaultValue({"13:00", "17:00", "21:00"}) List<LocalTime> reminderTimes,
        @DefaultValue("30") int catchUpMinutes,
        @DefaultValue("7") int quoteRepeatDays) {
}
