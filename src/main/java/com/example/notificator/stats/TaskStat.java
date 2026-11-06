package com.example.notificator.stats;

/**
 * Статистика по одному делу за период.
 *
 * @param title     название дела
 * @param daysDone  в скольких днях дело выполнено
 * @param daysTotal сколько дней дело было в списке (если добавили в середине недели — меньше 7)
 */
public record TaskStat(String title, int daysDone, int daysTotal) {
}
