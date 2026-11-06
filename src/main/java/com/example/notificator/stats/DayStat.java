package com.example.notificator.stats;

import java.time.LocalDate;

/**
 * Итог одного дня: сколько дел было в списке и сколько выполнено.
 *
 * @param date  день
 * @param total дел в списке за день
 * @param done  выполнено
 */
public record DayStat(LocalDate date, int total, int done) {

    /** Процент выполнения за день, округлённый до целого. */
    public int percent() {
        return WeeklyStatsCalculator.percent(done, total);
    }
}
