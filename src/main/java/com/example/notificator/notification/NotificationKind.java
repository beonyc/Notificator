package com.example.notificator.notification;

/** Вид уведомления по расписанию. */
public enum NotificationKind {
    /** Утренний итог вчерашнего дня. */
    SUMMARY,
    /** Итоги закончившейся недели и метрика продуктивности (по понедельникам). */
    WEEKLY_REPORT,
    /** Напоминание о невыполненных делах с цитатой. */
    REMINDER
}
