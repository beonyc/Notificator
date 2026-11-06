package com.example.notificator.util;

/**
 * Помощник для сообщений с HTML-разметкой Telegram (parse_mode = HTML).
 *
 * Бот отправляет все сообщения с разметкой (&lt;b&gt;жирный&lt;/b&gt;, &lt;i&gt;курсив&lt;/i&gt;, &lt;s&gt;зачёркнутый&lt;/s&gt;).
 * Поэтому ЛЮБОЙ текст, который ввёл пользователь (название дела, введённое время) или который взят из
 * файла (цитаты), нужно пропускать через {@link #escape}: иначе символ «&lt;» или «&amp;» в названии дела
 * сломает разбор разметки, и Telegram откажется отправлять сообщение.
 */
public final class Html {

    private Html() {
    }

    /** Заменяет символы, значимые для HTML (&amp;, &lt;, &gt;), чтобы текст показывался как есть. */
    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
