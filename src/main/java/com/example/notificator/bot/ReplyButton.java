package com.example.notificator.bot;

/**
 * Inline-кнопка под сообщением (не зависит от Telegram-библиотеки).
 *
 * @param text         надпись на кнопке
 * @param callbackData строка, которую Telegram пришлёт боту при нажатии (до 64 байт)
 * @param style        цвет кнопки: {@link #DANGER} (красная), {@link #SUCCESS} (зелёная),
 *                     {@link #PRIMARY} (синяя) или null — обычный цвет. Telegram показывает цвет только в клиентах,
 *                     вышедших после 9 февраля 2026 года; в старых кнопка выглядит обычной, поэтому у важных
 *                     кнопок заметной сделана и сама надпись
 */
public record ReplyButton(String text, String callbackData, String style) {

    /** Красная кнопка: опасное или необратимое действие (удалить все). */
    public static final String DANGER = "danger";

    /** Зелёная кнопка: подтверждающее, «хорошее» действие (выполнить все). */
    public static final String SUCCESS = "success";

    /** Синяя кнопка: главное действие экрана. */
    public static final String PRIMARY = "primary";

    /** Обычная кнопка без цвета. */
    public ReplyButton(String text, String callbackData) {
        this(text, callbackData, null);
    }
}
