package com.example.notificator.stats;

import com.example.notificator.model.TaskEntry;
import com.example.notificator.model.User;
import com.example.notificator.repository.TaskEntryRepository;
import com.example.notificator.repository.UserRepository;
import com.example.notificator.service.UserNotRegisteredException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Загружает отметки пользователя из БД и считает статистику через {@link WeeklyStatsCalculator}.
 * Неделя — с понедельника по воскресенье, «сегодня» считается по часовому поясу пользователя.
 */
@Service
@Transactional(readOnly = true)
public class StatsService {

    /** На сколько дней назад загружаем отметки: хватает на прошлую неделю и на длинную серию 100%. */
    private static final int LOOKBACK_DAYS = 60;

    private final UserRepository users;
    private final TaskEntryRepository entries;
    private final Clock clock;

    public StatsService(UserRepository users, TaskEntryRepository entries, Clock clock) {
        this.users = users;
        this.entries = entries;
        this.clock = clock;
    }

    /** Текущая неделя: с понедельника по сегодня (команда /week). */
    public WeeklyStats currentWeek(long chatId) {
        User user = users.findByTelegramChatId(chatId)
                .orElseThrow(() -> new UserNotRegisteredException(chatId));
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(user.getTimezone())));
        return forPeriod(user, mondayOf(today), today, true);
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
