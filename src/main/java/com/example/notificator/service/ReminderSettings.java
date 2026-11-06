package com.example.notificator.service;

import java.time.LocalTime;
import java.util.List;

/**
 * Настройки напоминаний пользователя для показа в боте.
 *
 * @param enabled  включены ли напоминания
 * @param times    времена напоминаний (свои или по умолчанию), по возрастанию
 * @param custom   true — времена заданы пользователем, false — стандартные
 * @param timezone часовой пояс, по которому считаются времена
 */
public record ReminderSettings(boolean enabled, List<LocalTime> times, boolean custom, String timezone) {
}
