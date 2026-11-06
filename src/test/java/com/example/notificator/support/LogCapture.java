package com.example.notificator.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Перехватывает строки лога указанного класса, чтобы тест мог проверить, что именно записано.
 * Используется в try-with-resources: после блока перехватчик снимается и уровень логгера возвращается.
 *
 * Пример: {@code try (LogCapture log = new LogCapture(TrackerService.class)) { ...; log.messages(); }}
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(Class<?> source) {
        this.logger = (Logger) LoggerFactory.getLogger(source);
        this.previousLevel = logger.getLevel();
        // DEBUG, чтобы тест видел и подробные сообщения; фильтровать по уровню можно через events()
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    /** Все записанные события (с уровнем, текстом и MDC). */
    public List<ILoggingEvent> events() {
        return appender.list;
    }

    /** Тексты всех записанных сообщений (после подстановки аргументов). */
    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Есть ли среди записанных сообщений такое, что содержит фрагмент. */
    public boolean contains(String fragment) {
        return messages().stream().anyMatch(m -> m.contains(fragment));
    }

    /** Первое событие, чьё сообщение содержит фрагмент (для проверки уровня и MDC). */
    public ILoggingEvent find(String fragment) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "В логе нет сообщения с «" + fragment + "». Записано: " + messages()));
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }
}
