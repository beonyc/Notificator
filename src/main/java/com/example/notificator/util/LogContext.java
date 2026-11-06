package com.example.notificator.util;

import org.slf4j.MDC;

/**
 * Контекст логирования: номер чата пользователя.
 *
 * MDC (Mapped Diagnostic Context) — это «записка», привязанная к текущему потоку. Пока она лежит,
 * шаблон лога (logging.pattern в application.yaml, часть {@code %X{chatId}}) дописывает номер чата
 * к КАЖДОЙ строке, в том числе из сервисов, которые про чат ничего не знают. Так в логе видно,
 * чей шаг, и не нужно передавать chatId в каждое сообщение вручную.
 *
 * Использование: {@code try (var ignored = LogContext.forChat(chatId)) { ... }} — после блока
 * прежнее значение восстанавливается автоматически (в отличие от стандартного {@code MDC.putCloseable},
 * который при закрытии просто удаляет ключ и «затирает» значение внешнего блока).
 */
public final class LogContext {

    /** Ключ в MDC; тот же ключ указан в шаблоне лога. */
    public static final String CHAT_ID = "chatId";

    private LogContext() {
    }

    /** Закрываемая «область» контекста: close() возвращает значение, которое было до {@link #forChat}. */
    public interface Scope extends AutoCloseable {
        @Override
        void close(); // без проверяемых исключений, чтобы try-with-resources не требовал catch
    }

    /** Помечает строки лога текущего потока номером чата до закрытия возвращённой области. */
    public static Scope forChat(long chatId) {
        String previous = MDC.get(CHAT_ID);
        MDC.put(CHAT_ID, String.valueOf(chatId));
        return () -> {
            if (previous == null) {
                MDC.remove(CHAT_ID);
            } else {
                MDC.put(CHAT_ID, previous);
            }
        };
    }
}
