package com.example.notificator.notification;

import java.time.LocalDateTime;

/**
 * Уведомление, которое подошло по расписанию и ждёт отправки.
 *
 * @param userId id пользователя в БД
 * @param chatId id чата Telegram
 * @param kind   вид уведомления
 * @param slot   назначенное время (по часовому поясу пользователя), например 2026-11-07T13:00;
 *               по нему отмечается, что слот обработан
 * @param text   текст сообщения; null — слать нечего (например, все дела выполнены),
 *               но слот всё равно нужно отметить обработанным
 */
public record PendingNotification(long userId, long chatId, NotificationKind kind,
                                  LocalDateTime slot, String text) {
}
