package com.example.notificator.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogTextAndContextTest {

    @Test
    void oneLineJoinsLinesWithMarker() {
        assertEquals("Строка 1 ⏎ Строка 2", LogText.oneLine("Строка 1\nСтрока 2"));
        assertEquals("Строка 1 ⏎ Строка 2", LogText.oneLine("Строка 1\r\nСтрока 2"));
        assertEquals("А ⏎ Б", LogText.oneLine("А\n\n  \nБ")); // пустые строки не плодят маркеры
    }

    @Test
    void oneLineTrimsOuterWhitespaceAndHandlesNull() {
        assertEquals("текст", LogText.oneLine("  \n текст \n "));
        assertEquals("∅", LogText.oneLine(null));
        assertEquals("", LogText.oneLine(""));
    }

    @Test
    void oneLineKeepsTextOfAllowedLengthAsIs() {
        String text = "я".repeat(LogText.MAX_LENGTH);

        assertEquals(text, LogText.oneLine(text));
    }

    @Test
    void oneLineTruncatesLongTextAndSaysHowMuchWasCut() {
        String result = LogText.oneLine("я".repeat(LogText.MAX_LENGTH + 25));

        assertEquals("я".repeat(LogText.MAX_LENGTH) + "… (+25 симв.)", result);
    }

    @Test
    void oneLineLeavesNoLineBreaks() {
        assertFalse(LogText.oneLine("a\nb\rc\r\nd e").matches("(?s).*\\R.*"));
    }

    @Test
    void forChatPutsChatIdInMdcAndRemovesItOnClose() {
        assertNull(MDC.get(LogContext.CHAT_ID));

        try (var ignored = LogContext.forChat(488697693L)) {
            assertEquals("488697693", MDC.get(LogContext.CHAT_ID));
        }

        assertNull(MDC.get(LogContext.CHAT_ID));
    }

    @Test
    void forChatRestoresPreviousValueWhenNested() {
        try (var outer = LogContext.forChat(1L)) {
            try (var inner = LogContext.forChat(2L)) {
                assertEquals("2", MDC.get(LogContext.CHAT_ID));
            }
            assertEquals("1", MDC.get(LogContext.CHAT_ID));
        }
        assertTrue(MDC.get(LogContext.CHAT_ID) == null);
    }

    @Test
    void mdcKeyMatchesLoggingPatternInApplicationYaml() throws Exception {
        // ключ MDC и шаблон лога должны совпадать, иначе номер чата пропадёт из строк лога
        String yaml = new String(getClass().getResourceAsStream("/application.yaml").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(yaml.contains("%X{" + LogContext.CHAT_ID + ":--}"), "в logging.pattern нет %X{chatId}");
    }
}
