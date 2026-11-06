package com.example.notificator.notification;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Тесты планировщика: порядок «отправить, затем отметить» и устойчивость к ошибкам. */
class NotificationSchedulerTest {

    private static final LocalDateTime SLOT = LocalDateTime.of(2026, 11, 7, 13, 0);

    private NotificationService service;
    private Notifier notifier;
    private NotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        notifier = mock(Notifier.class);
        scheduler = new NotificationScheduler(service, notifier);
    }

    @Test
    void sendsMessageAndThenMarksSlotHandled() {
        when(service.userIds()).thenReturn(List.of(1L));
        when(service.prepare(1L)).thenReturn(Optional.of(pending(1L, 10L, "Текст")));
        when(notifier.send(10L, "Текст")).thenReturn(true);

        scheduler.tick();

        verify(notifier).send(10L, "Текст");
        verify(service).markHandled(1L, NotificationKind.REMINDER, SLOT);
    }

    @Test
    void doesNotMarkSlotWhenSendingFailsSoItIsRetried() {
        when(service.userIds()).thenReturn(List.of(1L));
        when(service.prepare(1L)).thenReturn(Optional.of(pending(1L, 10L, "Текст")));
        when(notifier.send(anyLong(), anyString())).thenReturn(false);

        scheduler.tick();

        verify(service, never()).markHandled(anyLong(), any(), any());
    }

    @Test
    void marksSlotWithoutSendingWhenThereIsNothingToSay() {
        when(service.userIds()).thenReturn(List.of(1L));
        when(service.prepare(1L)).thenReturn(Optional.of(pending(1L, 10L, null)));

        scheduler.tick();

        verify(notifier, never()).send(anyLong(), anyString());
        verify(service).markHandled(1L, NotificationKind.REMINDER, SLOT);
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        when(service.userIds()).thenReturn(List.of(1L));
        when(service.prepare(1L)).thenReturn(Optional.empty());

        scheduler.tick();

        verify(notifier, never()).send(anyLong(), anyString());
        verify(service, never()).markHandled(anyLong(), any(), any());
    }

    @Test
    void failureForOneUserDoesNotStopOthers() {
        when(service.userIds()).thenReturn(List.of(1L, 2L));
        when(service.prepare(1L)).thenThrow(new IllegalStateException("неверный часовой пояс"));
        when(service.prepare(2L)).thenReturn(Optional.of(pending(2L, 20L, "Текст")));
        when(notifier.send(20L, "Текст")).thenReturn(true);

        scheduler.tick();

        verify(service).markHandled(2L, NotificationKind.REMINDER, SLOT);
    }

    private static PendingNotification pending(long userId, long chatId, String text) {
        return new PendingNotification(userId, chatId, NotificationKind.REMINDER, SLOT, text);
    }
}
