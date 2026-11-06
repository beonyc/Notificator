package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/** Тесты регистрации меню команд. Telegram заменён моком. */
class BotMenuInitializerTest {

    @Test
    void sendsAllCommandsToTelegram() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);

        new BotMenuInitializer(client).registerMenu();

        ArgumentCaptor<SetMyCommands> captor = ArgumentCaptor.forClass(SetMyCommands.class);
        verify(client).execute(captor.capture());
        List<String> names = captor.getValue().getCommands().stream().map(BotCommand::getCommand).toList();
        assertEquals(List.of("today", "add", "remove", "start"), names);
    }

    @Test
    void everyMenuCommandIsKnownToHandler() {
        // Защита от рассинхрона: команда из меню не должна попадать в ответ «Не понял».
        CommandHandler handler = new CommandHandler(mock(com.example.notificator.service.TrackerService.class));
        for (BotCommand command : BotMenuInitializer.COMMANDS) {
            String text = handler.handle(1L, "/" + command.getCommand()).text();
            assertTrue(!text.startsWith("Не понял"), "Команда /" + command.getCommand() + " не обрабатывается");
        }
    }

    @Test
    void telegramFailureDoesNotStopApplication() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(SetMyCommands.class))).thenThrow(new TelegramApiException("нет сети"));

        assertDoesNotThrow(() -> new BotMenuInitializer(client).registerMenu());
    }
}
