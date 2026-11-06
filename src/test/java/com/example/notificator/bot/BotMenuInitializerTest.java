package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.notificator.service.ReminderSettings;
import com.example.notificator.service.ReminderSettingsService;
import com.example.notificator.service.TrackerService;
import com.example.notificator.stats.StatsService;
import com.example.notificator.stats.WeeklyStats;
import java.time.LocalDate;
import java.time.LocalTime;
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
        assertEquals(List.of("today", "add", "remove", "week", "reminders", "start"), names);
    }

    @Test
    void everyMenuCommandIsKnownToHandler() {
        // Защита от рассинхрона: команда из меню не должна попадать в ответ «Не понял».
        ReminderSettingsService reminderSettings = mock(ReminderSettingsService.class);
        when(reminderSettings.get(1L)).thenReturn(
                new ReminderSettings(true, List.of(LocalTime.of(13, 0)), false, "Europe/Moscow"));
        StatsService stats = mock(StatsService.class);
        when(stats.currentWeek(1L)).thenReturn(new WeeklyStats(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 7),
                0, 0, 0, List.of(), List.of(), null, null, 0, null, 0, "Проленился"));
        CommandHandler handler = new CommandHandler(mock(TrackerService.class), reminderSettings, stats);
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
