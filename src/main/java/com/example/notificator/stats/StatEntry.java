package com.example.notificator.stats;

import java.time.LocalDate;

/**
 * Отметка по делу за день в упрощённом виде для расчёта статистики (без сущностей Hibernate:
 * калькулятор остаётся чистой функцией, которую легко тестировать).
 */
public record StatEntry(LocalDate date, long taskId, String title, boolean done) {
}
