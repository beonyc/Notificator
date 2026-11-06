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
 * @param wide         true — кнопка занимает весь ряд (длинная), даже если в экране кнопки идут по две в ряд;
 *                     false — кнопка делит ряд с соседними
 */
public record ReplyButton(String text, String callbackData, String style, boolean wide) {

    /** Красная кнопка: опасное или необратимое действие (удалить все). */
    public static final String DANGER = "danger";

    /** Зелёная кнопка: подтверждающее, «хорошее» действие (выполнить все). */
    public static final String SUCCESS = "success";

    /** Синяя кнопка: главное действие экрана. */
    public static final String PRIMARY = "primary";

    /** Обычная кнопка без цвета, делит ряд с соседними. */
    public ReplyButton(String text, String callbackData) {
        this(text, callbackData, null, false);
    }

    /** Цветная кнопка, делит ряд с соседними. */
    public ReplyButton(String text, String callbackData, String style) {
        this(text, callbackData, style, false);
    }

    /** Обычная длинная кнопка: занимает весь ряд. */
    public static ReplyButton wide(String text, String callbackData) {
        return new ReplyButton(text, callbackData, null, true);
    }
}
