package com.example.notificator.util;

/** Подготовка текста для строки лога: многострочные сообщения бота превращаем в одну строку. */
public final class LogText {

    /** Сколько символов текста максимум пишем в одну строку лога. */
    public static final int MAX_LENGTH = 300;

    private LogText() {
    }

    /**
     * Делает из текста одну строку для лога: переводы строк заменяются на « ⏎ », длинный текст обрезается
     * с пометкой, сколько символов не поместилось. Так сообщение бота не ломает построчный формат лога.
     */
    public static String oneLine(String text) {
        if (text == null) {
            return "∅";
        }
        String line = text.strip().replaceAll("\\s*\\R\\s*", " ⏎ ");
        if (line.length() <= MAX_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LENGTH) + "… (+" + (line.length() - MAX_LENGTH) + " симв.)";
    }
}
