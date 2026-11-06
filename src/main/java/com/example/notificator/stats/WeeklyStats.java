package com.example.notificator.stats;

import java.time.LocalDate;
import java.util.List;

/**
 * Статистика за период (неделя или её часть) и метрика продуктивности.
 *
 * @param from            первый день периода
 * @param to              последний день периода
 * @param total           всего отметок (дел по дням)
 * @param done            выполнено
 * @param percent         процент выполнения 0–100
 * @param days            дни, по которым есть данные, по возрастанию даты
 * @param tasks           статистика по каждому делу
 * @param best            лучший день по проценту выполнения (null, если данных нет)
 * @param worst           худший день (null, если данных нет)
 * @param streak          серия: сколько дней подряд выполнено 100%
 * @param previousPercent процент за аналогичный период прошлой недели; null, если там нет данных
 * @param score           Productivity score 0–100
 * @param verdict         оценка словами по score
 */
public record WeeklyStats(LocalDate from, LocalDate to, int total, int done, int percent,
                          List<DayStat> days, List<TaskStat> tasks, DayStat best, DayStat worst,
                          int streak, Integer previousPercent, int score, String verdict) {

    /** Есть ли за период хоть одна отметка. */
    public boolean hasData() {
        return total > 0;
    }
}
