package com.example.notificator.bot;

import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.service.UserNotRegisteredException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Разбирает текст сообщения как команду бота и возвращает текст ответа.
 *
 * Класс намеренно НЕ зависит от Telegram: на входе строка, на выходе строка. Поэтому его
 * можно проверить обычным юнит-тестом, а отправкой сообщений занимается {@link NotificatorBot}.
 */
@Component
public class CommandHandler {

    /** Ограничение длины названия дела, чтобы не засорять БД и сообщения. */
    static final int MAX_TITLE_LENGTH = 200;

    static final String HELP = """
            Команды:
            /add Читать 30 минут — добавить дело
            /today — дела на сегодня""";

    private final TrackerService service;

    public CommandHandler(TrackerService service) {
        this.service = service;
    }

    /**
     * @param chatId id чата Telegram (однозначно определяет пользователя)
     * @param text   текст сообщения, например «/add Читать 30 минут»
     * @return текст, который нужно отправить пользователю
     */
    public String handle(long chatId, String text) {
        // Разделяем «/add Читать» на команду и аргументы (максимум на 2 части).
        String[] parts = text.trim().split("\\s+", 2);
        // В группах Telegram дописывает имя бота: «/today@MyBot» — отбрасываем его.
        String command = parts[0].split("@", 2)[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        try {
            return switch (command) {
                case "/start" -> start(chatId);
                case "/add" -> add(chatId, args);
                case "/today" -> today(chatId);
                default -> "Не понял. " + HELP;
            };
        } catch (UserNotRegisteredException e) {
            return "Сначала нажми /start.";
        }
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

    private String today(long chatId) {
        List<TodayItem> items = service.todayItems(chatId);
        if (items.isEmpty()) {
            return "На сегодня дел нет. Добавь: /add Читать 30 минут";
        }
        StringBuilder sb = new StringBuilder("Дела на сегодня:\n");
        int doneCount = 0;
        for (int i = 0; i < items.size(); i++) {
            TodayItem item = items.get(i);
            sb.append(item.done() ? "✅ " : "⬜ ")
                    .append(i + 1).append(". ").append(item.title()).append('\n');
            if (item.done()) {
                doneCount++;
            }
        }
        return sb.append("\nВыполнено ").append(doneCount).append(" из ").append(items.size())
                .toString();
    }
}
