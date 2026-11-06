package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/** Тесты отправки уведомлений. Telegram заменён моком. */
class TelegramNotifierTest {

    @Test
    void sendsTextToChatAndReportsSuccess() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);

        boolean sent = new TelegramNotifier(client).send(55L, "Привет");

        assertTrue(sent);
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertEquals("55", captor.getValue().getChatId());
        assertEquals("Привет", captor.getValue().getText());
    }

    @Test
    void reportsFailureInsteadOfThrowing() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("нет сети"));

        assertFalse(new TelegramNotifier(client).send(55L, "Привет"));
    }
}
