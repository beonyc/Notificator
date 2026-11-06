package com.example.notificator.bot;

import com.example.notificator.service.MarkResult;
import com.example.notificator.service.TaskItem;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import java.util.ArrayList;
import java.util.List;
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

    private static final CallbackResponse UNKNOWN_ACTION = new CallbackResponse("Неизвестное действие", null);

    static final String HELP = """
            Команды:
            /add Читать 30 минут — добавить дело
            /today — дела на сегодня (с кнопками «выполнено»)
            /remove — убрать дело из списка""";

    private final TrackerService service;

    public CommandHandler(TrackerService service) {
        this.service = service;
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
            return switch (command) {
                case "/start" -> BotReply.text(start(chatId));
                case "/add" -> BotReply.text(add(chatId, args));
                case "/today" -> today(chatId);
                case "/remove" -> remove(chatId);
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
        sb.append("\nВыполнено ").append(doneCount).append(" из ").append(items.size());
        if (buttons.isEmpty()) {
            sb.append("\nВсе дела выполнены, день закрыт 💪");
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
        return new BotReply(sb.toString().stripTrailing(), buttons);
    }

    private static String shorten(String title) {
        return title.length() <= MAX_BUTTON_TITLE_LENGTH
                ? title
                : title.substring(0, MAX_BUTTON_TITLE_LENGTH - 1) + "…";
    }
}
