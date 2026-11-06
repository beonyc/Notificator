package com.example.notificator.service;

import com.example.notificator.config.NotificationProperties;
import com.example.notificator.model.User;
import com.example.notificator.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Личные настройки напоминаний: свои времена, количество (сколько времён — столько напоминаний),
 * включение и выключение. Если пользователь ничего не менял, действуют времена из application.yaml.
 *
 * Любое изменение «обнуляет» уже наступившие сегодня слоты (см. {@link #markPastSlotsHandled}),
 * чтобы настройка не вызывала немедленного напоминания «с опозданием».
 */
@Service
@Transactional
public class ReminderSettingsService {

    private static final Logger log = LoggerFactory.getLogger(ReminderSettingsService.class);

    /** Максимум напоминаний в день, чтобы бот не превратился в спам. */
    public static final int MAX_REMINDERS = 6;

    private final UserRepository users;
    private final NotificationProperties props;
    private final Clock clock;

    public ReminderSettingsService(UserRepository users, NotificationProperties props, Clock clock) {
        this.users = users;
        this.props = props;
        this.clock = clock;
    }

    /** Текущие настройки пользователя. */
    @Transactional(readOnly = true)
    public ReminderSettings get(long chatId) {
        User user = findUser(chatId);
        return new ReminderSettings(user.areRemindersEnabled(), timesOf(user),
                user.getReminderTimes() != null, user.getTimezone());
    }

    /**
     * Задаёт свои времена напоминаний и включает их. Количество напоминаний = количество времён.
     *
     * @param times от 1 до {@link #MAX_REMINDERS} времён; повторы убираются, порядок не важен
     * @throws IllegalArgumentException если список пуст или слишком длинный
     */
    public void setTimes(long chatId, List<LocalTime> times) {
        List<LocalTime> normalized = times.stream().distinct().sorted().toList();
        if (normalized.isEmpty() || normalized.size() > MAX_REMINDERS) {
            throw new IllegalArgumentException("Нужно от 1 до " + MAX_REMINDERS + " времён");
        }
        User user = findUser(chatId);
        user.setReminderTimes(normalized.stream().map(LocalTime::toString).collect(Collectors.joining(",")));
        user.setRemindersEnabled(true);
        markPastSlotsHandled(user);
        log.info("Напоминания: заданы свои времена {} (всего {}), включены", user.getReminderTimes(), normalized.size());
    }

    /** Включает или выключает напоминания, не трогая сохранённые времена. */
    public void setEnabled(long chatId, boolean enabled) {
        User user = findUser(chatId);
        user.setRemindersEnabled(enabled);
        markPastSlotsHandled(user);
        log.info("Напоминания: {}", enabled ? "включены" : "выключены");
    }

    /** Возвращает стандартные времена из настроек приложения и включает напоминания. */
    public void resetToDefault(long chatId) {
        User user = findUser(chatId);
        user.setReminderTimes(null);
        user.setRemindersEnabled(true);
        markPastSlotsHandled(user);
        log.info("Напоминания: возвращено стандартное время {}, включены", timesOf(user));
    }

    /**
     * Времена напоминаний пользователя, по которым их реально надо слать: пусто, если напоминания
     * выключены. Используется планировщиком уведомлений.
     */
    public List<LocalTime> activeTimes(User user) {
        return user.areRemindersEnabled() ? timesOf(user) : List.of();
    }

    /** Свои времена пользователя либо стандартные, по возрастанию. */
    private List<LocalTime> timesOf(User user) {
        if (user.getReminderTimes() == null) {
            return props.reminderTimes().stream().sorted().toList();
        }
        return Arrays.stream(user.getReminderTimes().split(",")).map(LocalTime::parse).sorted().toList();
    }

    /**
     * Считает все уже наступившие сегодня слоты обработанными. Иначе, если, например, в 14:40 поставить
     * напоминание на 14:30, оно пришло бы сразу «с опозданием на 10 минут» (это окно догоняния).
     * Новые времена начинают действовать со следующего наступившего слота.
     */
    private void markPastSlotsHandled(User user) {
        user.setLastReminderSlot(LocalDateTime.ofInstant(clock.instant(), ZoneId.of(user.getTimezone())));
    }

    private User findUser(long chatId) {
        return users.findByTelegramChatId(chatId)
                .orElseThrow(() -> new UserNotRegisteredException(chatId));
    }
}
