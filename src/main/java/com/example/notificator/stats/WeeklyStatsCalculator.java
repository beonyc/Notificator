package com.example.notificator.stats;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Расчёт недельной статистики и Productivity score. Чистая функция: на входе список отметок,
 * на выходе {@link WeeklyStats}; БД и время не нужны, поэтому все формулы легко проверять тестами.
 *
 * Формулы (см. SPEC.md):
 * - процент выполнения = выполнено / всего;
 * - стабильность = доля дней, в которых выполнено не меньше {@value #GOOD_DAY_PERCENT}%;
 * - Productivity score = 70% × процент выполнения + 30% × стабильность (0–100);
 * - оценка: 90+ «Огонь», 70–89 «Хорошо», 50–69 «Средне», меньше 50 «Проленился».
 */
public final class WeeklyStatsCalculator {

    /** День считается «хорошим» (для стабильности), если выполнено не меньше стольки процентов. */
    public static final int GOOD_DAY_PERCENT = 80;

    private static final double COMPLETION_WEIGHT = 0.7;
    private static final double STABILITY_WEIGHT = 0.3;

    private WeeklyStatsCalculator() {
    }

    /**
     * @param all        отметки за период и за предыдущие дни (для сравнения с прошлой неделей и серии)
     * @param from       первый день периода
     * @param to         последний день периода
     * @param inProgress true, если последний день ещё не закончился (запрос /week): незавершённый
     *                   сегодняшний день не обрывает серию 100%
     */
    public static WeeklyStats calculate(List<StatEntry> all, LocalDate from, LocalDate to, boolean inProgress) {
        List<StatEntry> period = between(all, from, to);
        int total = period.size();
        int done = (int) period.stream().filter(StatEntry::done).count();
        int percent = percent(done, total);

        List<DayStat> days = dayStats(period);
        DayStat best = days.stream()
                .max(Comparator.comparingInt(DayStat::percent).thenComparing(DayStat::date, Comparator.reverseOrder()))
                .orElse(null);
        DayStat worst = days.stream()
                .min(Comparator.comparingInt(DayStat::percent).thenComparing(DayStat::date))
                .orElse(null);

        int score = score(percent, stability(days));

        // Сравниваем с тем же по длине периодом прошлой недели (для /week — те же дни недели).
        List<StatEntry> previous = between(all, from.minusDays(7), to.minusDays(7));
        Integer previousPercent = previous.isEmpty()
                ? null
                : percent((int) previous.stream().filter(StatEntry::done).count(), previous.size());

        return new WeeklyStats(from, to, total, done, percent, days, taskStats(period), best, worst,
                streak(all, to, inProgress), previousPercent, score, verdict(score));
    }

    /** Процент выполнения, округлённый до целого; 0, если дел нет. */
    static int percent(int done, int total) {
        return total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
    }

    /** Стабильность 0–100: какая доля дней (из дней с данными) выполнена не меньше чем на 80%. */
    static double stability(List<DayStat> days) {
        if (days.isEmpty()) {
            return 0;
        }
        long good = days.stream().filter(d -> d.percent() >= GOOD_DAY_PERCENT).count();
        return good * 100.0 / days.size();
    }

    /** Productivity score: 70% процент выполнения + 30% стабильность, округлённый до целого. */
    static int score(int completionPercent, double stabilityPercent) {
        return (int) Math.round(COMPLETION_WEIGHT * completionPercent + STABILITY_WEIGHT * stabilityPercent);
    }

    /** Оценка словами по score. */
    public static String verdict(int score) {
        if (score >= 90) {
            return "Огонь";
        }
        if (score >= 70) {
            return "Хорошо";
        }
        if (score >= 50) {
            return "Средне";
        }
        return "Проленился";
    }

    /**
     * Серия: сколько дней подряд (назад от последнего дня периода) выполнено ровно 100%.
     * День без данных или с невыполненными делами обрывает серию. Незаконченный сегодняшний день
     * (inProgress) серию не обрывает, но и не добавляет: считаем с вчерашнего дня.
     */
    static int streak(List<StatEntry> all, LocalDate to, boolean inProgress) {
        Map<LocalDate, DayStat> byDate = new TreeMap<>();
        dayStats(all.stream().filter(e -> !e.date().isAfter(to)).toList())
                .forEach(d -> byDate.put(d.date(), d));

        LocalDate day = to;
        DayStat last = byDate.get(to);
        if (inProgress && (last == null || last.percent() < 100)) {
            day = to.minusDays(1);
        }
        int streak = 0;
        while (true) {
            DayStat stat = byDate.get(day);
            if (stat == null || stat.percent() < 100) {
                return streak;
            }
            streak++;
            day = day.minusDays(1);
        }
    }

    private static List<StatEntry> between(List<StatEntry> all, LocalDate from, LocalDate to) {
        return all.stream().filter(e -> !e.date().isBefore(from) && !e.date().isAfter(to)).toList();
    }

    /** Итоги по дням, по возрастанию даты; дни без отметок не включаются. */
    private static List<DayStat> dayStats(List<StatEntry> entries) {
        Map<LocalDate, int[]> counts = new TreeMap<>(); // дата -> {всего, выполнено}
        for (StatEntry e : entries) {
            int[] c = counts.computeIfAbsent(e.date(), d -> new int[2]);
            c[0]++;
            if (e.done()) {
                c[1]++;
            }
        }
        return counts.entrySet().stream()
                .map(en -> new DayStat(en.getKey(), en.getValue()[0], en.getValue()[1]))
                .toList();
    }

    /** По каждому делу: в скольких днях выполнено из скольких дней в списке; порядок — по id дела. */
    private static List<TaskStat> taskStats(List<StatEntry> entries) {
        Map<Long, String> titles = new TreeMap<>();
        Map<Long, int[]> counts = new LinkedHashMap<>();
        for (StatEntry e : entries) {
            titles.putIfAbsent(e.taskId(), e.title());
            int[] c = counts.computeIfAbsent(e.taskId(), id -> new int[2]);
            c[0]++;
            if (e.done()) {
                c[1]++;
            }
        }
        return titles.entrySet().stream()
                .map(t -> new TaskStat(t.getValue(), counts.get(t.getKey())[1], counts.get(t.getKey())[0]))
                .toList();
    }
}
