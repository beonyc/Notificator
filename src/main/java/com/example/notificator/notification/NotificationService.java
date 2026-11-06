package com.example.notificator.notification;

import com.example.notificator.config.NotificationProperties;
import com.example.notificator.model.Quote;
import com.example.notificator.model.TaskEntry;
import com.example.notificator.model.User;
import com.example.notificator.repository.TaskEntryRepository;
import com.example.notificator.repository.UserRepository;
import com.example.notificator.service.QuoteService;
import com.example.notificator.service.ReminderSettingsService;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.stats.ReportFormatter;
import com.example.notificator.stats.StatsService;
import com.example.notificator.stats.WeeklyStats;
import com.example.notificator.util.Plurals;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Решает, какое уведомление (утренний итог или напоминание) пора отправить пользователю, и
 * формирует его текст. Сам ничего не отправляет: отправкой занимается {@link NotificationScheduler}.
 *
 * Как избегаем дублей и потерь:
 * - время сравнивается по часовому поясу пользователя, а не сервера;
 * - планировщик вызывает проверку каждую минуту, а уведомление считается «подошедшим», если назначенное
 *   время уже наступило, но прошло не больше catch-up-minutes (так не теряем уведомление, если
 *   бот на минуту завис или Telegram не ответил);
 * - обработанный слот запоминается в БД (User.lastSummaryDate / lastReminderSlot), поэтому дубли
 *   не уходят ни в следующую минуту, ни после перезапуска приложения.
 */
@Service
public class NotificationService {

    /** Сколько дел максимум перечисляем в напоминании (остальные сворачиваем в «и ещё N»). */
    static final int MAX_LISTED_TASKS = 15;

    private final UserRepository users;
    private final TaskEntryRepository entries;
    private final TrackerService tracker;
    private final QuoteService quotes;
    private final ReminderSettingsService reminderSettings;
    private final StatsService statsService;
    private final NotificationProperties props;
    private final Clock clock;

    public NotificationService(UserRepository users, TaskEntryRepository entries, TrackerService tracker,
                               QuoteService quotes, ReminderSettingsService reminderSettings,
                               StatsService statsService, NotificationProperties props, Clock clock) {
        this.users = users;
        this.entries = entries;
        this.tracker = tracker;
        this.quotes = quotes;
        this.reminderSettings = reminderSettings;
        this.statsService = statsService;
        this.props = props;
        this.clock = clock;
    }

    /** id всех пользователей: по ним планировщик обходит проверку каждую минуту. */
    @Transactional(readOnly = true)
    public List<Long> userIds() {
        return users.findAll().stream().map(User::getId).toList();
    }

    /**
     * Возвращает уведомление, которое сейчас пора отправить пользователю, либо пусто.
     * Утренний итог имеет приоритет; если подошло и напоминание, оно уйдёт при следующей проверке.
     */
    @Transactional
    public Optional<PendingNotification> prepare(long userId) {
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(user.getTimezone()));
        LocalDate date = now.toLocalDate();

        LocalDateTime summarySlot = date.atTime(props.summaryTime());
        if (isDue(now, summarySlot)
                && (user.getLastSummaryDate() == null || user.getLastSummaryDate().isBefore(date))) {
            return Optional.of(new PendingNotification(userId, user.getTelegramChatId(),
                    NotificationKind.SUMMARY, summarySlot, buildSummary(user, date)));
        }

        // Недельный отчёт: по понедельникам присылаем итоги только что закончившейся недели (пн–вс).
        LocalDateTime weeklySlot = date.atTime(props.weeklyReportTime());
        if (date.getDayOfWeek() == DayOfWeek.MONDAY && isDue(now, weeklySlot)
                && (user.getLastWeeklyReportDate() == null || user.getLastWeeklyReportDate().isBefore(date))) {
            return Optional.of(new PendingNotification(userId, user.getTelegramChatId(),
                    NotificationKind.WEEKLY_REPORT, weeklySlot, buildWeeklyReport(user, date)));
        }

        // Берём последний наступивший слот напоминаний: более ранние уже неактуальны.
        // Времена у каждого пользователя свои (или стандартные); если напоминания выключены — список пуст.
        Optional<LocalDateTime> reminderSlot = reminderSettings.activeTimes(user).stream()
                .sorted(Comparator.reverseOrder())
                .map(date::atTime)
                .filter(slot -> !now.isBefore(slot))
                .findFirst();
        if (reminderSlot.isPresent() && isDue(now, reminderSlot.get())
                && (user.getLastReminderSlot() == null || user.getLastReminderSlot().isBefore(reminderSlot.get()))) {
            return Optional.of(new PendingNotification(userId, user.getTelegramChatId(),
                    NotificationKind.REMINDER, reminderSlot.get(), buildReminder(user)));
        }
        return Optional.empty();
    }

    /**
     * Запоминает, что слот обработан (сообщение отправлено или слать было нечего),
     * чтобы он не повторился.
     */
    @Transactional
    public void markHandled(long userId, NotificationKind kind, LocalDateTime slot) {
        User user = users.findById(userId).orElseThrow();
        switch (kind) {
            case SUMMARY -> user.setLastSummaryDate(slot.toLocalDate());
            case WEEKLY_REPORT -> user.setLastWeeklyReportDate(slot.toLocalDate());
            case REMINDER -> user.setLastReminderSlot(slot);
        }
    }

    /**
     * Итоги прошедшей недели (с понедельника по воскресенье перед {@code monday}) и метрика продуктивности.
     *
     * @return текст; null, если за неделю не было ни одного дела (нечего считать)
     */
    private String buildWeeklyReport(User user, LocalDate monday) {
        WeeklyStats stats = statsService.forPeriod(user, monday.minusDays(7), monday.minusDays(1), false);
        return stats.hasData() ? ReportFormatter.format(stats, true) : null;
    }

    /** Слот «подошёл»: назначенное время наступило, и с тех пор прошло не больше catch-up-minutes. */
    private boolean isDue(LocalDateTime now, LocalDateTime slot) {
        return !now.isBefore(slot) && now.isBefore(slot.plusMinutes(props.catchUpMinutes()));
    }

    /**
     * Итог вчерашнего дня и план на сегодня. Заодно создаёт отметки на сегодня
     * (так делает и /today): в этом смысл утреннего «старта нового дня».
     *
     * @return текст; null, если сообщать нечего (вчера дел не было и сегодня список пуст)
     */
    private String buildSummary(User user, LocalDate today) {
        List<TaskEntry> yesterday = entries.findByTaskUserAndDate(user, today.minusDays(1));
        List<TodayItem> todayItems = tracker.todayItems(user.getTelegramChatId());

        StringBuilder sb = new StringBuilder();
        if (!yesterday.isEmpty()) {
            long done = yesterday.stream().filter(TaskEntry::isDone).count();
            long total = yesterday.size();
            sb.append("Вчера выполнено ").append(done).append(" из ").append(total)
                    .append(" (").append(Math.round(done * 100.0 / total)).append("%).\n");
            sb.append(done == total
                    ? "Красава, день закрыт полностью."
                    : "Не доделано: " + (total - done) + ". Сегодня без отговорок.\n"
                            + "Выполнил, но не успел отметить? Исправь: /week → «Дела по дням» → вчера.");
        }
        if (!todayItems.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("Сегодня в списке: ").append(todayItems.size()).append(' ')
                    .append(Plurals.plural(todayItems.size(), "дело", "дела", "дел"))
                    .append(". Вставай и делай — /today");
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Напоминание о невыполненных делах с мотивационной цитатой.
     *
     * @return текст; null, если дел нет или все выполнены (тогда не беспокоим)
     */
    private String buildReminder(User user) {
        List<TodayItem> items = tracker.todayItems(user.getTelegramChatId());
        List<TodayItem> notDone = items.stream().filter(i -> !i.done()).toList();
        if (notDone.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("Ты ещё не сделал ")
                .append(notDone.size()).append(" из ").append(items.size()).append(". Вставай и делай.\n\n");
        notDone.stream().limit(MAX_LISTED_TASKS)
                .forEach(i -> sb.append("⬜ ").append(i.title()).append('\n'));
        if (notDone.size() > MAX_LISTED_TASKS) {
            sb.append("…и ещё ").append(notDone.size() - MAX_LISTED_TASKS).append('\n');
        }
        Optional<Quote> quote = quotes.pickQuote();
        quote.ifPresent(q -> sb.append("\n«").append(q.getText()).append("» — ").append(q.getAuthor()).append('\n'));
        sb.append("\nОтметить выполненное: /today");
        return sb.toString();
    }
}
