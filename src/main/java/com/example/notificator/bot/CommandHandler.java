package com.example.notificator.bot;

import com.example.notificator.service.EditResult;
import com.example.notificator.service.MarkResult;
import com.example.notificator.service.ReminderSettings;
import com.example.notificator.service.ReminderSettingsService;
import com.example.notificator.service.TaskItem;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import com.example.notificator.stats.DayReport;
import com.example.notificator.stats.DayStat;
import com.example.notificator.stats.ReportFormatter;
import com.example.notificator.stats.StatsService;
import com.example.notificator.stats.WeekOverview;
import com.example.notificator.util.Html;
import com.example.notificator.util.Visuals;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * Кнопки под отчётом /week: «Дела по дням» (выбор дня текущей недели), «К отчёту» (назад к отчёту)
     * и кнопка дня с префиксом «day:2026-11-05». Просмотр прошлых дней доступен только из /week.
     */
    static final String DAYS = "days";
    static final String WEEK = "week";
    static final String DAY_PREFIX = "day:";

    /** Префикс кнопки исправления статуса вчерашнего дела: «yd:<день>:<id отметки>:<1 — выполнено, 0 — нет>». */
    static final String EDIT_PREFIX = "yd:";

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM");
    private static final Locale RU = Locale.forLanguageTag("ru");

    /** Время в команде /reminders: «9», «9:00», «09.30» (час 0–23, минуты 0–59 проверяются отдельно). */
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})(?:[:.](\\d{2}))?$");

    private static final CallbackResponse UNKNOWN_ACTION = new CallbackResponse("Неизвестное действие", null);

    /** Список команд для приветствия и подсказки. Текст с HTML-разметкой (&lt;b&gt;): бот шлёт всё с parse_mode=HTML. */
    static final String HELP = """
            <b>Что я умею:</b>
            ➕ /add Читать 30 минут — добавить дело
            📋 /today — дела на сегодня
            🗑 /remove — убрать дело из списка
            📊 /week — итоги недели и дела по дням
            ⏰ /reminders — время напоминаний""";

    private final TrackerService service;
    private final ReminderSettingsService reminderSettings;
    private final StatsService statsService;

    /**
     * Чаты, от которых бот ждёт время напоминаний (после нажатия «Изменить время»).
     * Хранится в памяти: после перезапуска бота ожидание сбрасывается — это безопасно,
     * пользователь просто нажмёт кнопку ещё раз. Набор потокобезопасный.
     */
    private final Set<Long> awaitingTimes = ConcurrentHashMap.newKeySet();

    public CommandHandler(TrackerService service, ReminderSettingsService reminderSettings,
                          StatsService statsService) {
        this.service = service;
        this.reminderSettings = reminderSettings;
        this.statsService = statsService;
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
                case "/cancel" -> BotReply.text(awaitingTimes.remove(chatId) ? "↩️ Отменено." : "🤷 Отменять нечего.");
                case "/start" -> BotReply.text(start(chatId));
                case "/add" -> BotReply.text(add(chatId, args));
                case "/today" -> today(chatId);
                case "/remove" -> remove(chatId);
                case "/reminders" -> reminders(chatId, args);
                case "/week" -> weekReport(chatId);
                default -> BotReply.text("🤔 Не понял.\n\n" + HELP);
            };
        } catch (UserNotRegisteredException e) {
            return BotReply.text("👋 Сначала нажми /start.");
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
                case WEEK:
                    return new CallbackResponse(null, weekReport(chatId));
                case DAYS:
                    return new CallbackResponse(null, daysPicker(chatId));
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
            if (data.startsWith(EDIT_PREFIX)) {
                return editYesterday(chatId, data);
            }
            if (data.startsWith(DAY_PREFIX)) {
                return showDay(chatId, data.substring(DAY_PREFIX.length()));
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
                "⚠️ <b>Удалить все дела (" + count + ")?</b>\nЭто нельзя отменить.",
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
            return "👋 <b>Привет!</b> Я помогу не забивать на цели: ты ставишь дела на день, "
                    + "я напоминаю и считаю статистику.\n\n" + HELP;
        }
        return "✅ Ты уже зарегистрирован.\n\n" + HELP;
    }

    private String add(long chatId, String title) {
        if (title.isEmpty()) {
            // Примеры в <code>: по нажатию Telegram копирует строку целиком, остаётся вставить и отправить.
            // (Обычная команда /add в тексте при нажатии отправила бы пустой /add ещё раз.)
            return """
                    ✍️ <b>Напиши название дела после команды.</b>
                    Например:

                    <code>/add Читать 30 минут</code>
                    <code>/add Сделать зарядку</code>
                    <code>/add Выучить 10 английских слов</code>
                    <code>/add Позвонить родителям</code>

                    💡 Нажми на пример, чтобы скопировать, и измени под себя.""";
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return "⚠️ Слишком длинное название (максимум " + MAX_TITLE_LENGTH + " символов).";
        }
        service.addTask(chatId, title);
        return "✅ Добавлено: <b>" + Html.escape(title) + "</b>";
    }

    /** Список дел на сегодня; под ним — кнопка «✅» для каждого невыполненного дела. */
    private BotReply today(long chatId) {
        List<TodayItem> items = service.todayItems(chatId);
        if (items.isEmpty()) {
            return BotReply.text("📭 На сегодня дел нет.\nДобавь: /add Читать 30 минут");
        }
        StringBuilder sb = new StringBuilder("📋 <b>Дела на сегодня</b>\n\n");
        List<ReplyButton> buttons = new ArrayList<>();
        int doneCount = 0;
        for (int i = 0; i < items.size(); i++) {
            TodayItem item = items.get(i);
            String title = Html.escape(item.title());
            // Выполненное дело зачёркиваем: список сразу видно «на глаз».
            sb.append(item.done() ? "✅ " : "⬜ ").append(i + 1).append(". ")
                    .append(item.done() ? "<s>" + title + "</s>" : title).append('\n');
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
        int percent = Visuals.percent(doneCount, items.size());
        sb.append('\n').append(Visuals.bar(percent)).append(' ').append(percent).append("%\n")
                .append("Выполнено <b>").append(doneCount).append("</b> из <b>").append(items.size()).append("</b>");
        if (buttons.isEmpty()) {
            sb.append("\n\n🎉 Все дела выполнены, день закрыт 💪");
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
                return remindersView(chatId, "✅ Готово, напоминания выключены.\n\n");
            }
            case "on", "вкл", "включить" -> {
                reminderSettings.setEnabled(chatId, true);
                return remindersView(chatId, "✅ Готово, напоминания включены.\n\n");
            }
            case "default", "сброс" -> {
                reminderSettings.resetToDefault(chatId);
                return remindersView(chatId, "✅ Готово, вернул стандартное время.\n\n");
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
        String hint = awaiting ? "\n↩️ Попробуй ещё раз или /cancel, чтобы отменить." : "";
        List<LocalTime> times = new ArrayList<>();
        for (String token : text.split("[\\s,;]+")) {
            Matcher m = TIME.matcher(token);
            int hour = m.matches() ? Integer.parseInt(m.group(1)) : -1;
            int minute = m.matches() && m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            if (hour < 0 || hour > 23 || minute > 59) {
                // token — ввод пользователя, поэтому экранируем (иначе «<» сломает HTML-разметку)
                return BotReply.text("⚠️ Не понял время «" + Html.escape(token)
                        + "». Пример: <code>9:00 14:30 20:00</code>" + hint);
            }
            times.add(LocalTime.of(hour, minute));
        }
        if (times.stream().distinct().count() > ReminderSettingsService.MAX_REMINDERS) {
            return BotReply.text("⚠️ Слишком много: максимум " + ReminderSettingsService.MAX_REMINDERS
                    + " напоминаний в день." + hint);
        }
        reminderSettings.setTimes(chatId, times);
        awaitingTimes.remove(chatId);
        return remindersView(chatId, "✅ Готово, время напоминаний обновлено.\n\n");
    }

    /** Кнопка «✏️ Изменить время»: просим прислать время обычным сообщением и запоминаем, что ждём его. */
    private CallbackResponse askForTimes(long chatId) {
        ReminderSettings s = reminderSettings.get(chatId); // заодно проверяет, что пользователь зарегистрирован
        awaitingTimes.add(chatId);
        BotReply prompt = new BotReply(
                "⏰ <b>Отправь время напоминаний</b> одним сообщением, например:\n"
                        + "<code>9:00 14:30 20:00</code>\n\n"
                        + "Сколько времён — столько напоминаний в день (до " + ReminderSettingsService.MAX_REMINDERS
                        + "). Формат 24 часа, время по твоему часовому поясу (" + Html.escape(s.timezone()) + ").",
                List.of(new ReplyButton("↩️ Отмена", REMINDERS_CANCEL)));
        return new CallbackResponse("Жду время", prompt);
    }

    /** Текущие настройки напоминаний и кнопки «включить/выключить» и «по умолчанию». */
    private BotReply remindersView(long chatId, String prefix) {
        ReminderSettings s = reminderSettings.get(chatId);
        String times = s.times().stream().map(LocalTime::toString).collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder(prefix);
        if (s.enabled()) {
            sb.append("🔔 <b>Напоминания:</b> ").append(times).append(s.custom() ? "" : " (по умолчанию)");
        } else {
            sb.append("🔕 <b>Напоминания выключены.</b>\nСохранённое время: ").append(times);
        }
        sb.append("\n🌍 Часовой пояс: ").append(Html.escape(s.timezone())).append('.');
        sb.append("\n\n💡 Чтобы изменить время, нажми «✏️ Изменить время» и отправь, например: ")
                .append("<code>9:00 14:30 20:00</code>")
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

    /**
     * Отчёт за текущую неделю (команда /week и кнопка «К отчёту»). Под ним кнопка перехода к просмотру
     * дел по дням: просмотр прошлых дней доступен только отсюда, отдельной команды для него нет.
     */
    private BotReply weekReport(long chatId) {
        return new BotReply(ReportFormatter.format(statsService.currentWeek(chatId), false),
                List.of(new ReplyButton("📅 Дела по дням", DAYS)));
    }

    /** Нажатие кнопки дня («day:2026-11-05»). Дата приходит от клиента, поэтому проверяется сервисом. */
    private CallbackResponse showDay(long chatId, String isoDate) {
        LocalDate date;
        try {
            date = LocalDate.parse(isoDate);
        } catch (DateTimeParseException e) {
            return UNKNOWN_ACTION;
        }
        return showDay(chatId, date);
    }

    private CallbackResponse showDay(long chatId, LocalDate date) {
        DayReport report = statsService.dayReport(chatId, date);
        if (report.status() == DayReport.Status.OK) {
            return new CallbackResponse(null, dayView(report));
        }
        return new CallbackResponse(dayError(report.status(), statsService.weekOverview(chatId)),
                daysPicker(chatId));
    }

    /**
     * Нажатие кнопки исправления статуса вчерашнего дела: «yd:2026-11-06:15:1» — день, id отметки и желаемый
     * статус (1 — выполнено, 0 — не выполнено). Данные приходят от клиента: любые отклонения от формата
     * игнорируются, а права и «вчера ли это» проверяет сервис.
     */
    private CallbackResponse editYesterday(long chatId, String data) {
        String[] parts = data.split(":");
        if (parts.length != 4 || !(parts[3].equals("0") || parts[3].equals("1"))) {
            return UNKNOWN_ACTION;
        }
        LocalDate date;
        long entryId;
        try {
            date = LocalDate.parse(parts[1]);
            entryId = Long.parseLong(parts[2]);
        } catch (DateTimeParseException | NumberFormatException e) {
            return UNKNOWN_ACTION;
        }

        EditResult result = service.setYesterdayDone(chatId, entryId, parts[3].equals("1"));
        String notice = switch (result) {
            case MARKED_DONE -> "Отмечено ✅";
            case MARKED_UNDONE -> "Отметка снята";
            case UNCHANGED -> "Статус уже такой";
            case NOT_EDITABLE -> "Исправлять можно только вчерашний день";
            case NOT_FOUND -> "Дело не найдено";
        };
        // Всегда показываем актуальный список дня, чтобы сообщение не устаревало.
        return new CallbackResponse(notice, showDay(chatId, date).view());
    }

    private static String dayError(DayReport.Status status, WeekOverview week) {
        return status == DayReport.Status.FUTURE
                ? "Этот день ещё не наступил."
                : "Можно смотреть только текущую неделю (" + SHORT_DATE.format(week.monday()) + "–"
                        + SHORT_DATE.format(week.sunday()) + ").";
    }

    /**
     * Выбор дня текущей недели: кнопка на каждый день от понедельника до сегодня с итогом дня
     * и кнопка возврата к отчёту.
     */
    private BotReply daysPicker(long chatId) {
        WeekOverview week = statsService.weekOverview(chatId);
        List<ReplyButton> buttons = new ArrayList<>();
        for (DayStat d : week.days()) {
            String title = capitalize(d.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, RU))
                    + " " + SHORT_DATE.format(d.date())
                    + (d.date().equals(week.today()) ? " (сегодня)"
                            : d.date().equals(week.today().minusDays(1)) ? " (вчера)" : "")
                    + " · " + (d.total() == 0 ? "дел нет" : d.done() + " из " + d.total());
            buttons.add(new ReplyButton(title, DAY_PREFIX + d.date()));
        }
        buttons.add(new ReplyButton("↩️ К отчёту", WEEK));
        return new BotReply("📅 <b>Выбери день этой недели</b> (" + SHORT_DATE.format(week.monday()) + "–"
                + SHORT_DATE.format(week.sunday()) + "):", buttons);
    }

    /**
     * Список дел за день: ✅ выполнено, ❌ не выполнено (для прошедших дней), ⬜ ещё не выполнено (для
     * сегодняшнего, день не закончен). Внизу итог и кнопка возврата к выбору дня.
     */
    private BotReply dayView(DayReport report) {
        String name = capitalize(report.date().getDayOfWeek().getDisplayName(TextStyle.FULL, RU));
        StringBuilder sb = new StringBuilder("📅 <b>").append(name).append(", ")
                .append(SHORT_DATE.format(report.date())).append("</b>")
                .append(report.today() ? " (сегодня)" : report.editable() ? " (вчера)" : "").append('\n');

        List<TodayItem> items = report.items();
        if (items.isEmpty()) {
            sb.append("\n📭 В этот день дел не было.");
        } else {
            int done = 0;
            sb.append('\n');
            for (TodayItem item : items) {
                sb.append(item.done() ? "✅ " : report.today() ? "⬜ " : "❌ ")
                        .append(Html.escape(item.title())).append('\n');
                if (item.done()) {
                    done++;
                }
            }
            int percent = Visuals.percent(done, items.size());
            sb.append('\n').append(Visuals.bar(percent)).append(' ').append(percent).append("%\n")
                    .append("Выполнено <b>").append(done).append("</b> из <b>").append(items.size()).append("</b>");
        }
        if (report.today()) {
            sb.append("\n\n💡 Отмечать дела: /today");
        }

        List<ReplyButton> buttons = new ArrayList<>();
        if (report.editable() && !items.isEmpty()) {
            // Вчерашний день ещё можно исправить: кнопка на каждое дело меняет его статус на противоположный.
            sb.append("\n\n💡 Не успел вчера? Нажми на дело ниже, чтобы изменить его статус.");
            for (TodayItem item : items) {
                buttons.add(new ReplyButton(
                        (item.done() ? "↩️ Снять: " : "✅ Отметить: ") + shorten(item.title()),
                        EDIT_PREFIX + report.date() + ":" + item.entryId() + ":" + (item.done() ? "0" : "1")));
            }
        }
        buttons.add(new ReplyButton("↩️ К дням недели", DAYS));
        return new BotReply(sb.toString(), buttons);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(RU) + s.substring(1);
    }

    /** Список дел для удаления: по кнопке «🗑» на каждое активное дело. */
    private BotReply remove(long chatId) {
        List<TaskItem> items = service.activeTasks(chatId);
        if (items.isEmpty()) {
            return BotReply.text("📭 Дел для удаления нет.\nДобавь: /add Читать 30 минут");
        }
        StringBuilder sb = new StringBuilder("🗑 <b>Какое дело убрать из списка?</b>\n\n");
        List<ReplyButton> buttons = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            TaskItem item = items.get(i);
            sb.append(i + 1).append(". ").append(Html.escape(item.title())).append('\n');
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
