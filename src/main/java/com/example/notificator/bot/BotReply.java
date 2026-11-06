package com.example.notificator.bot;

import java.util.List;

/**
 * Ответ бота: текст и (необязательно) кнопки под ним, по одной кнопке в строке.
 * Telegram-объекты из этих данных собирает {@link NotificatorBot}.
 */
public record BotReply(String text, List<ReplyButton> buttons) {

    /** Ответ без кнопок. */
    public static BotReply text(String text) {
        return new BotReply(text, List.of());
    }
}
