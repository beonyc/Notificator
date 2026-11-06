package com.example.notificator.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * Ответ бота: текст (с HTML-разметкой) и (необязательно) кнопки под ним.
 * Telegram-объекты из этих данных собирает {@link NotificatorBot}.
 *
 * @param text    текст сообщения
 * @param buttons кнопки по порядку
 * @param columns сколько кнопок в одном ряду (1 — каждая кнопка на своей строке, 2 — по две в ряд)
 */
public record BotReply(String text, List<ReplyButton> buttons, int columns) {

    /** Кнопки по одной в ряду (так показывает большинство экранов). */
    public BotReply(String text, List<ReplyButton> buttons) {
        this(text, buttons, 1);
    }

    /** Ответ без кнопок. */
    public static BotReply text(String text) {
        return new BotReply(text, List.of());
    }

    /** Тот же ответ с ещё одной кнопкой в конце. */
    public BotReply plusButton(ReplyButton button) {
        List<ReplyButton> all = new ArrayList<>(buttons);
        all.add(button);
        return new BotReply(text, List.copyOf(all), columns);
    }
}
