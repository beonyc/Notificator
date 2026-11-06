package com.example.notificator.stats;

import java.time.LocalDate;
import java.util.List;

/**
 * Обзор текущей недели по дням: понедельник, сегодня и итог каждого дня от понедельника до сегодня
 * (дни без дел тоже присутствуют, у них total = 0). Будущие дни недели не включаются.
 * В понедельник в начало списка добавляется вчерашний день (воскресенье прошлой недели),
 * чтобы его статусы можно было исправить.
 *
 * @param monday понедельник текущей недели
 * @param today  сегодняшний день по часовому поясу пользователя
 * @param days   дни от понедельника (в понедельник — от вчера) до сегодня включительно
 */
public record WeekOverview(LocalDate monday, LocalDate today, List<DayStat> days) {

    /** Воскресенье текущей недели — последний день, который можно смотреть. */
    public LocalDate sunday() {
        return monday.plusDays(6);
    }
}
