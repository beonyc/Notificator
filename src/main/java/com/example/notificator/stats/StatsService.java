package com.example.notificator.stats;

import com.example.notificator.model.TaskEntry;
import com.example.notificator.model.User;
import com.example.notificator.repository.TaskEntryRepository;
import com.example.notificator.repository.UserRepository;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Загружает отметки пользователя из БД и считает статистику через {@link WeeklyStatsCalculator}.
 * Неделя — с понедельника по воскресенье, «сегодня» считается по часовому поясу пользователя.
 */
@Service
@Transactional(readOnly = true)
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    /** На сколько дней назад загружаем отметки: хватает на прошлую неделю и на длинную серию 100%. */
    private static final int LOOKBACK_DAYS = 60;

    private final UserRepository users;
    private final TaskEntryRepository entries;
    private final TrackerService tracker;
    private final Clock clock;

    public StatsService(UserRepository users, TaskEntryRepository entries, TrackerService tracker, Clock clock) {
        this.users = users;
        this.entries = entries;
        this.tracker = tracker;
        this.clock = clock;
    }

    /** Текущая неделя: с понедельника по сегодня (команда /week). */
    public WeeklyStats currentWeek(long chatId) {
        User user = findUser(chatId);
        LocalDate today = today(user);
        WeeklyStats stats = forPeriod(user, mondayOf(today), today, true);
        log.info("Статистика недели {}–{}: выполнено {} из {} ({}%), оценка {}/100 «{}», серия {}",
                stats.from(), stats.to(), stats.done(), stats.total(), stats.percent(),
                stats.score(), stats.verdict(), stats.streak());
        return stats;
    }

    private User findUser(long chatId) {
        return users.findByTelegramChatId(chatId)
                .orElseThrow(() -> new UserNotRegisteredException(chatId));
    }

    /** «Сегодня» по часовому поясу пользователя, а не сервера. */
    private LocalDate today(User user) {
        return LocalDate.now(clock.withZone(ZoneId.of(user.getTimezone())));
    }

    /**
     * Обзор текущей недели по дням: от понедельника до сегодня, с количеством дел и выполненных за каждый день.
     * Для сегодняшнего дня заодно создаются отметки (как в /today), чтобы цифры совпадали со списком дел.
     * Метод не только читает, поэтому объявлен {@code @Transactional} без readOnly (иначе Hibernate
     * не сохранил бы создаваемые отметки).
     */
    @Transactional
    public WeekOverview weekOverview(long chatId) {
        User user = findUser(chatId);
        LocalDate today = today(user);
        LocalDate monday = mondayOf(today);

        // В понедельник вчерашний день — это воскресенье прошлой недели. Его тоже показываем в списке,
        // иначе дела за вчера нельзя было бы исправить (см. dayReport).
        LocalDate first = today.isAfter(monday) ? monday : today.minusDays(1);

        List<TodayItem> todayItems = tracker.todayItems(chatId);
        Map<LocalDate, DayStat> past = new HashMap<>();
        Map<LocalDate, List<TaskEntry>> byDate = entries
                .findByTaskUserAndDateBetween(user, first, today.minusDays(1)).stream()
                .collect(Collectors.groupingBy(TaskEntry::getDate));
        byDate.forEach((date, list) -> past.put(date,
                new DayStat(date, list.size(), (int) list.stream().filter(TaskEntry::isDone).count())));

        List<DayStat> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            if (d.equals(today)) {
                days.add(new DayStat(d, todayItems.size(), (int) todayItems.stream().filter(TodayItem::done).count()));
            } else {
                days.add(past.getOrDefault(d, new DayStat(d, 0, 0)));
            }
        }
        return new WeekOverview(monday, today, days);
    }

    /**
     * Список дел и их статус за один день текущей недели.
     *
     * Дату присылает клиент (она зашита в кнопке и может быть подделана), поэтому проверяем границы:
     * текущая неделя (пн–вс), не позже сегодняшнего дня, плюс вчерашний день даже если он в прошлой неделе
     * (в понедельник это воскресенье — иначе его статус нельзя было бы исправить).
     * Для сегодняшнего дня используется тот же список, что и в /today (создаёт недостающие отметки,
     * не показывает удалённые дела); для прошлых дней — сохранённые отметки, включая выполненные дела,
     * которые потом удалили. Вчерашний день помечается как доступный для исправления статусов.
     */
    @Transactional
    public DayReport dayReport(long chatId, LocalDate date) {
        DayReport report = buildDayReport(chatId, date);
        if (report.status() == DayReport.Status.OK) {
            log.info("Просмотр дня {}: дел {}{}{}", date, report.items().size(),
                    report.today() ? ", сегодня" : "", report.editable() ? ", вчера (можно исправлять)" : "");
        } else {
            // будущий день или не текущая неделя: обычно устаревшая или поддельная кнопка
            log.warn("Просмотр дня {} отклонён: {}", date, report.status());
        }
        return report;
    }

    private DayReport buildDayReport(long chatId, LocalDate date) {
        User user = findUser(chatId);
        LocalDate today = today(user);
        LocalDate monday = mondayOf(today);
        LocalDate yesterday = today.minusDays(1);

        boolean inCurrentWeek = !date.isBefore(monday) && !date.isAfter(monday.plusDays(6));
        if (!inCurrentWeek && !date.equals(yesterday)) {
            return new DayReport(date, DayReport.Status.OUT_OF_WEEK, List.of(), false, false);
        }
        if (date.isAfter(today)) {
            return new DayReport(date, DayReport.Status.FUTURE, List.of(), false, false);
        }
        if (date.equals(today)) {
            return new DayReport(date, DayReport.Status.OK, tracker.todayItems(chatId), true, false);
        }
        List<TodayItem> items = entries.findByTaskUserAndDate(user, date).stream()
                .sorted(Comparator.comparing(TaskEntry::getId))
                .map(e -> new TodayItem(e.getId(), e.getTask().getTitle(), e.isDone()))
                .toList();
        return new DayReport(date, DayReport.Status.OK, items, false, date.equals(yesterday));
    }

    /**
     * Статистика за период.
     *
     * @param inProgress true, если последний день периода ещё идёт
     */
    public WeeklyStats forPeriod(User user, LocalDate from, LocalDate to, boolean inProgress) {
        List<StatEntry> data = entries
                .findByTaskUserAndDateBetween(user, from.minusDays(LOOKBACK_DAYS), to).stream()
                .map(StatsService::toStatEntry)
                .toList();
        return WeeklyStatsCalculator.calculate(data, from, to, inProgress);
    }

    /** Понедельник той недели, в которую попадает дата. */
    public static LocalDate mondayOf(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    private static StatEntry toStatEntry(TaskEntry e) {
        return new StatEntry(e.getDate(), e.getTask().getId(), e.getTask().getTitle(), e.isDone());
    }
}
