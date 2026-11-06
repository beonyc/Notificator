package com.example.notificator.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.example.notificator.support.LogCapture;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Проверяет, что планировщик пишет в лог каждый шаг доставки уведомления и помечает строки номером чата. */
class NotificationSchedulerLoggingTest {

    private static final LocalDateTime SLOT = LocalDateTime.of(2026, 11, 7, 13, 0);

    private NotificationService service;
    private Notifier notifier;
    private NotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        notifier = mock(Notifier.class);
        scheduler = new NotificationScheduler(service, notifier);
        when(service.userIds()).thenReturn(List.of(1L));
    }

    private static PendingNotification pending(String text) {
        return new PendingNotification(1L, 777L, NotificationKind.REMINDER, SLOT, text);
    }

    @Test
    void sentNotificationIsLoggedStepByStepWithChatId() {
        when(service.prepare(1L)).thenReturn(Optional.of(pending("Напоминание\nвторая строка")));
        List<String> mdcDuringSend = new ArrayList<>();
        when(notifier.send(anyLong(), anyString())).thenAnswer(invocation -> {
            mdcDuringSend.add(MDC.get("chatId"));
            return true;
        });

        try (LogCapture log = new LogCapture(NotificationScheduler.class)) {
            scheduler.tick();

            String due = log.find("Подошло уведомление").getFormattedMessage();
            assertTrue(due.contains("REMINDER") && due.contains("2026-11-07T13:00"), due);
            assertTrue(due.contains("Напоминание ⏎ вторая строка"), due); // текст одной строкой
            assertTrue(log.contains("отправлено, слот отмечен обработанным"), log.messages().toString());
            assertEquals("777", log.find("Подошло уведомление").getMDCPropertyMap().get("chatId"));
        }
        assertEquals(List.of("777"), mdcDuringSend);
        assertNull(MDC.get("chatId"), "номер чата должен быть убран из MDC после доставки");
    }

    @Test
    void failedSendIsLoggedAsWarningAndSlotStaysOpen() {
        when(service.prepare(1L)).thenReturn(Optional.of(pending("Текст")));
        when(notifier.send(anyLong(), anyString())).thenReturn(false);

        try (LogCapture log = new LogCapture(NotificationScheduler.class)) {
            scheduler.tick();

            assertEquals(Level.WARN, log.find("не отправлено, повторим в следующую минуту").getLevel());
            assertFalse(log.contains("слот отмечен обработанным"), log.messages().toString());
        }
    }

    @Test
    void skippedNotificationWithoutTextIsLogged() {
        when(service.prepare(1L)).thenReturn(Optional.of(pending(null)));

        try (LogCapture log = new LogCapture(NotificationScheduler.class)) {
            scheduler.tick();

            assertTrue(log.contains("слать нечего"), log.messages().toString());
            assertTrue(log.contains("пропущено, слот отмечен обработанным"), log.messages().toString());
        }
    }

    @Test
    void quietTickWritesOnlyDebugRecordSoLogIsNotFlooded() {
        when(service.prepare(1L)).thenReturn(Optional.empty());

        try (LogCapture log = new LogCapture(NotificationScheduler.class)) {
            scheduler.tick();

            assertTrue(log.events().stream().allMatch(e -> e.getLevel() == Level.DEBUG), log.messages().toString());
            assertTrue(log.contains("Проверка уведомлений: пользователей 1"));
        }
    }

    @Test
    void preparationFailureIsLoggedAsError() {
        when(service.prepare(1L)).thenThrow(new IllegalStateException("неверный часовой пояс"));

        try (LogCapture log = new LogCapture(NotificationScheduler.class)) {
            scheduler.tick();

            assertEquals(Level.ERROR, log.find("Ошибка при подготовке уведомления для пользователя 1").getLevel());
        }
    }
}
