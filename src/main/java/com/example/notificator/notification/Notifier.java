package com.example.notificator.notification;

/**
 * Отправка текстового сообщения пользователю. Интерфейс отделяет логику уведомлений от Telegram:
 * в тестах вместо настоящей отправки подставляется заглушка.
 */
public interface Notifier {

    /**
     * @return true, если сообщение отправлено; false — отправить не удалось (повторим в следующую минуту,
     *         пока слот не устарел)
     */
    boolean send(long chatId, String text);
}
