package com.example.notificator.bot;

/**
 * Inline-кнопка под сообщением (не зависит от Telegram-библиотеки).
 *
 * @param text         надпись на кнопке
 * @param callbackData строка, которую Telegram пришлёт боту при нажатии (до 64 байт)
 */
public record ReplyButton(String text, String callbackData) {
}
