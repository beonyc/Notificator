package com.example.notificator.bot;

import com.example.notificator.service.MarkResult;
import com.example.notificator.service.ReminderSettings;
import com.example.notificator.service.ReminderSettingsService;
import com.example.notificator.service.TaskItem;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Разбирает команды и нажатия кнопок и возвращает, что ответить пользователю.
 *
 * Класс намеренно НЕ зависит от Telegram: на входе строки, на выходе {@link BotReply} / {@link CallbackResponse}.
 * Поэтому его можно проверить обычным юнит-тестом, а общением с Telegram занимается {@link NotificatorBot}.
 */
@Component
public class CommandHandler {

    /** Ограничение длины названия дела, чтобы не засорять БД и сообщения. */
    static final int MAX_TITLE_LENGTH = 200;

    /** Длинные названия на кнопке обрезаем, чтобы она не растягивалась. */
    static final int MAX_BUTTON_TITLE_LENGTH = 30;

    /** Префикс callback-данных кнопки «выполнено»: «done:<id отметки>». */
    static final String DONE_PREFIX = "done:";

    /** Префикс callback-данных кнопки «удалить дело»: «rm:<id дела>». */
    static final String REMOVE_PREFIX = "rm:";

    /** Кнопка «Выполнить все» под списком /today (показывается, если невыполненных дел два и больше). */
    static final String DONE_ALL = "doneall";

    /** Кнопка «Удалить все» под списком /remove (показывается, если дел два и больше) и шаги подтверждения. */
    static final String REMOVE_ALL = "rmall";
    static final String REMOVE_ALL_YES = "rmall:yes";
    static final String REMOVE_ALL_NO = "rmall:no";

    /** Кнопки настроек напоминаний под сообщением /reminders. */
    static final String REMINDERS_ON = "rem:on";
    static final String REMINDERS_OFF = "rem:off";
    static final String REMINDERS_DEFAULT = "rem:default";
    static final String REMINDERS_EDIT = "rem:edit";
    static final String REMINDERS_CANCEL = "rem:cancel";

    /** Время в команде /reminders: «9», «9:00», «09.30» (час 0–23, минуты 0–59 проверяются отдельно). */
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})(?:[:.](\\d{2}))?$");

    private static final CallbackResponse UNKNOWN_ACTION = new CallbackResponse("Неизвестное действие", null);

    static final String HELP = """
            Команды:
            /add Читать 30 минут — добавить дело
            /today — дела на сегодня (с кнопками «выполнено»)
            /remove — убрать дело из списка
            /reminders — время напоминаний, выключить или включить""";

    private final TrackerService service;
    private final ReminderSettingsService reminderSettings;

    /**
     * Чаты, от которых бот ждёт время напоминаний (после нажатия «Изменить время»).
     * Хранится в памяти: после перезапуска бота ожидание сбрасывается — это безопасно,
     * пользователь просто нажмёт кнопку ещё раз. Набор потокобезопасный.
     */
    private final Set<Long> awaitingTimes = ConcurrentHashMap.newKeySet();

    public CommandHandler(TrackerService service, ReminderSettingsService reminderSettings) {
        this.service = service;
        this.reminderSettings = reminderSettings;
    }

    /**
     * Обрабатывает текстовое сообщение.
     *
     * @param chatId id чата Telegram (однозначно определяет пользователя)
     * @param text   текст сообщения, например «/add Читать 30 минут»
     */
    public BotReply handle(long chatId, String text) {
        // Разделяем «/add Читать» на команду и аргументы (максимум на 2 части).
        String[] parts = text.trim().split("\\s+", 2);
        // В группах Telegram дописывает имя бота: «/today@MyBot» — отбрасываем его.
        String command = parts[0].split("@", 2)[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        try {
            // Бот ждёт время после кнопки «Изменить время»: обычное сообщение — это и есть время.
            // Любая другая команда (кроме /cancel) означает «передумал» и сбрасывает ожидание.
            if (awaitingTimes.contains(chatId)) {
                if (!text.trim().startsWith("/")) {
                    return setTimesFromText(chatId, text.trim(), true);
                }
                if (!command.equals("/cancel")) {
                    awaitingTimes.remove(chatId);
                }
            }
            return switch (command) {
                case "/cancel" -> BotReply.text(awaitingTimes.remove(chatId) ? "Отменено." : "Отменять нечего.");
                case "/start" -> BotReply.text(start(chatId));
                case "/add" -> BotReply.text(add(chatId, args));
                case "/today" -> today(chatId);
                case "/remove" -> remove(chatId);
                case "/reminders" -> reminders(chatId, args);
                default -> BotReply.text("Не понял. " + HELP);
            };
        } catch (UserNotRegisteredException e) {
            return BotReply.text("Сначала нажми /start.");
        }
    }

    /**
     * Обрабатывает нажатие inline-кнопки (CallbackQuery).
     *
     * @param data строка, заданная при создании кнопки, например «done:15»
     */
    public CallbackResponse handleCallback(long chatId, String data) {
        if (data == null) {
            return UNKNOWN_ACTION;
        }
        try {
            // Кнопки без id сравниваем целиком; они не пересекаются с префиксами «done:» и «rm:».
            switch (data) {
                case DONE_ALL:
                    return markAllDone(chatId);
                case REMOVE_ALL:
                    return confirmRemoveAll(chatId);
                case REMOVE_ALL_YES:
                    return removeAll(chatId);
                case REMOVE_ALL_NO:
                    return new CallbackResponse("Отменено", remove(chatId));
                case REMINDERS_EDIT:
                    return askForTimes(chatId);
                case REMINDERS_CANCEL:
                    awaitingTimes.remove(chatId);
                    return new CallbackResponse("Отменено", remindersView(chatId, ""));
                case REMINDERS_ON:
                    awaitingTimes.remove(chatId);
                    reminderSettings.setEnabled(chatId, true);
                    return new CallbackResponse("Напоминания включены", remindersView(chatId, ""));
                case REMINDERS_OFF:
                    awaitingTimes.remove(chatId);
                    reminderSettings.setEnabled(chatId, false);
                    return new CallbackResponse("Напоминания выключены", remindersView(chatId, ""));
                case REMINDERS_DEFAULT:
                    awaitingTimes.remove(chatId);
                    reminderSettings.resetToDefault(chatId);
                    return new CallbackResponse("Время сброшено", remindersView(chatId, ""));
                default:
                    break;
            }
            if (data.startsWith(DONE_PREFIX)) {
                Long entryId = parseId(data, DONE_PREFIX);
                return entryId == null ? UNKNOWN_ACTION : markDone(chatId, entryId);
            }
            if (data.startsWith(REMOVE_PREFIX)) {
                Long taskId = parseId(data, REMOVE_PREFIX);
                return taskId == null ? UNKNOWN_ACTION : removeTask(chatId, taskId);
            }
            return UNKNOWN_ACTION;
        } catch (UserNotRegisteredException e) {
            return new CallbackResponse("Сначала нажми /start", null);
        }
    }

    /** Достаёт id из данных кнопки вида «done:15»; null, если после префикса не число. */
    private static Long parseId(String data, String prefix) {
        try {
            return Long.parseLong(data.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private CallbackResponse markDone(long chatId, long entryId) {
        MarkResult result = service.markDone(chatId, entryId);
        String notice = switch (result) {
            case DONE -> "Выполнено ✅";
            case ALREADY_DONE -> "Это дело уже выполнено";
            case EXPIRED -> "Этот день уже закончился";
            case NOT_FOUND -> "Дело не найдено";
        };
        // Всегда показываем актуальный список: так сообщение с кнопками не устаревает.
        return new CallbackResponse(notice, today(chatId));
    }

    private CallbackResponse markAllDone(long chatId) {
        int count = service.markAllDone(chatId);
        String notice = count == 0 ? "Все дела уже выполнены" : "Выполнено дел: " + count + " ✅";
        return new CallbackResponse(notice, today(chatId));
    }

    /**
     * Удаление всех дел необратимо, поэтому сначала просим подтверждение
     * (случайное нажатие не должно стирать весь список).
     */
    private CallbackResponse confirmRemoveAll(long chatId) {
        int count = service.activeTasks(chatId).size();
        if (count == 0) {
            return new CallbackResponse("Дел для удаления нет", remove(chatId));
        }
        BotReply confirm = new BotReply(
                "Удалить все дела (" + count + ")? Это нельзя отменить.",
                List.of(new ReplyButton("🗑 Да, удалить все", REMOVE_ALL_YES),
                        new ReplyButton("↩️ Отмена", REMOVE_ALL_NO)));
        return new CallbackResponse(null, confirm);
    }

    private CallbackResponse removeAll(long chatId) {
        int count = service.removeAllTasks(chatId);
        String notice = count == 0 ? "Дел для удаления нет" : "Удалено дел: " + count;
        return new CallbackResponse(notice, remove(chatId));
    }

    private CallbackResponse removeTask(long chatId, long taskId) {
        String notice = service.removeTask(chatId, taskId)
                .map(title -> "Удалено: " + shorten(title))
                .orElse("Дело не найдено");
        return new CallbackResponse(notice, remove(chatId));
    }

    private String start(long chatId) {
        if (service.register(chatId)) {
            return "Привет! Я помогу не забивать на цели: ты ставишь дела на день, "
                    + "я напоминаю и считаю статистику.\n\n" + HELP;
        }
        return "Ты уже зарегистрирован.\n\n" + HELP;
    }

    private String add(long chatId, String title) {
        if (title.isEmpty()) {
            return "Напиши название дела: /add Читать 30 минут";
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return "Слишком длинное название (максимум " + MAX_TITLE_LENGTH + " символов).";
        }
        service.addTask(chatId, title);
        return "Добавлено: " + title;
    }

    /** Список дел на сегодня; под ним — кнопка «✅» для каждого невыполненного дела. */
    private BotReply today(long chatId) {
        List<TodayItem> items = service.todayItems(chatId);
        if (items.isEmpty()) {
            return BotReply.text("На сегодня дел нет. Добавь: /add Читать 30 минут");
        }
        StringBuilder sb = new StringBuilder("Дела на сегодня:\n");
        List<ReplyButton> buttons = new ArrayList<>();
        int doneCount = 0;
        for (int i = 0; i < items.size(); i++) {
            TodayItem item = items.get(i);
            sb.append(item.done() ? "✅ " : "⬜ ")
                    .append(i + 1).append(". ").append(item.title()).append('\n');
            if (item.done()) {
                doneCount++;
            } else {
                buttons.add(new ReplyButton(
                        "✅ " + (i + 1) + ". " + shorten(item.title()),
                        DONE_PREFIX + item.entryId()));
            }
        }
        if (buttons.size() >= 2) {
            buttons.add(new ReplyButton("✅ Выполнить все", DONE_ALL));
        }
        sb.append("\nВыполнено ").append(doneCount).append(" из ").append(items.size());
        if (buttons.isEmpty()) {
            sb.append("\nВсе дела выполнены, день закрыт 💪");
        }
        return new BotReply(sb.toString(), buttons);
    }

    /**
     * Команда /reminders. Без аргументов показывает настройки; с аргументами меняет их:
     * «off» — выключить, «on» — включить, «default» — стандартные времена,
     * список времён («9:00 14:30 20:00») — свои напоминания (сколько времён, столько напоминаний в день).
     */
    private BotReply reminders(long chatId, String args) {
        if (args.isEmpty()) {
            return remindersView(chatId, "");
        }
        switch (args.toLowerCase()) {
            case "off", "выкл", "выключить" -> {
                reminderSettings.setEnabled(chatId, false);
                return remindersView(chatId, "Готово, напоминания выключены.\n\n");
            }
            case "on", "вкл", "включить" -> {
                reminderSettings.setEnabled(chatId, true);
                return remindersView(chatId, "Готово, напоминания включены.\n\n");
            }
            case "default", "сброс" -> {
                reminderSettings.resetToDefault(chatId);
                return remindersView(chatId, "Готово, вернул стандартное время.\n\n");
            }
            default -> {
                // дальше разбираем как список времён
            }
        }
        return setTimesFromText(chatId, args, false);
    }

    /**
     * Разбирает текст со временами («9:00 14:30 20:00») и, если всё верно, сохраняет их.
     * Используется и командой «/reminders 9:00 14:30», и режимом ожидания после кнопки «Изменить время».
     *
     * @param awaiting true, если бот ждёт время после кнопки: при ошибке напоминаем про /cancel,
     *                 а режим ожидания остаётся включённым (можно просто прислать исправленный вариант)
     */
    private BotReply setTimesFromText(long chatId, String text, boolean awaiting) {
        String hint = awaiting ? "\nПопробуй ещё раз или /cancel, чтобы отменить." : "";
        List<LocalTime> times = new ArrayList<>();
        for (String token : text.split("[\\s,;]+")) {
            Matcher m = TIME.matcher(token);
            int hour = m.matches() ? Integer.parseInt(m.group(1)) : -1;
            int minute = m.matches() && m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            if (hour < 0 || hour > 23 || minute > 59) {
                return BotReply.text("Не понял время «" + token + "». Пример: 9:00 14:30 20:00" + hint);
            }
            times.add(LocalTime.of(hour, minute));
        }
        if (times.stream().distinct().count() > ReminderSettingsService.MAX_REMINDERS) {
            return BotReply.text("Слишком много: максимум " + ReminderSettingsService.MAX_REMINDERS
                    + " напоминаний в день." + hint);
        }
        reminderSettings.setTimes(chatId, times);
        awaitingTimes.remove(chatId);
        return remindersView(chatId, "Готово, время напоминаний обновлено.\n\n");
    }

    /** Кнопка «✏️ Изменить время»: просим прислать время обычным сообщением и запоминаем, что ждём его. */
    private CallbackResponse askForTimes(long chatId) {
        ReminderSettings s = reminderSettings.get(chatId); // заодно проверяет, что пользователь зарегистрирован
        awaitingTimes.add(chatId);
        BotReply prompt = new BotReply(
                "Отправь время напоминаний одним сообщением, например:\n9:00 14:30 20:00\n\n"
                        + "Сколько времён — столько напоминаний в день (до " + ReminderSettingsService.MAX_REMINDERS
                        + "). Формат 24 часа, время по твоему часовому поясу (" + s.timezone() + ").",
                List.of(new ReplyButton("↩️ Отмена", REMINDERS_CANCEL)));
        return new CallbackResponse("Жду время", prompt);
    }

    /** Текущие настройки напоминаний и кнопки «включить/выключить» и «по умолчанию». */
    private BotReply remindersView(long chatId, String prefix) {
        ReminderSettings s = reminderSettings.get(chatId);
        String times = s.times().stream().map(LocalTime::toString).collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder(prefix);
        if (s.enabled()) {
            sb.append("🔔 Напоминания: ").append(times).append(s.custom() ? "" : " (по умолчанию)");
        } else {
            sb.append("🔕 Напоминания выключены.\nСохранённое время: ").append(times);
        }
        sb.append("\nВремя по твоему часовому поясу: ").append(s.timezone()).append('.');
        sb.append("\n\nЧтобы изменить время, нажми «✏️ Изменить время» и отправь, например: 9:00 14:30 20:00")
                .append("\n(до ").append(ReminderSettingsService.MAX_REMINDERS)
                .append(" штук; сколько времён — столько напоминаний в день)");

        List<ReplyButton> buttons = new ArrayList<>();
        buttons.add(new ReplyButton("✏️ Изменить время", REMINDERS_EDIT));
        buttons.add(s.enabled()
                ? new ReplyButton("🔕 Выключить", REMINDERS_OFF)
                : new ReplyButton("🔔 Включить", REMINDERS_ON));
        if (s.custom()) {
            buttons.add(new ReplyButton("↩️ По умолчанию", REMINDERS_DEFAULT));
        }
        return new BotReply(sb.toString(), buttons);
    }

    /** Список дел для удаления: по кнопке «🗑» на каждое активное дело. */
    private BotReply remove(long chatId) {
        List<TaskItem> items = service.activeTasks(chatId);
        if (items.isEmpty()) {
            return BotReply.text("Дел для удаления нет. Добавь: /add Читать 30 минут");
        }
        StringBuilder sb = new StringBuilder("Какое дело убрать из списка?\n");
        List<ReplyButton> buttons = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            TaskItem item = items.get(i);
            sb.append(i + 1).append(". ").append(item.title()).append('\n');
            buttons.add(new ReplyButton(
                    "🗑 " + (i + 1) + ". " + shorten(item.title()),
                    REMOVE_PREFIX + item.taskId()));
        }
        if (items.size() >= 2) {
            buttons.add(new ReplyButton("🗑 Удалить все", REMOVE_ALL));
        }
        return new BotReply(sb.toString().stripTrailing(), buttons);
    }

    private static String shorten(String title) {
        return title.length() <= MAX_BUTTON_TITLE_LENGTH
                ? title
                : title.substring(0, MAX_BUTTON_TITLE_LENGTH - 1) + "…";
    }
}
