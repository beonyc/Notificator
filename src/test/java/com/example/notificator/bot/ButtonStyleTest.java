package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.notificator.service.ReminderSettingsService;
import com.example.notificator.service.TaskItem;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.stats.StatsService;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/**
 * Проверяет цветовое выделение кнопок: «Удалить все» красная, «Выполнить все» зелёная, остальные обычные,
 * и что цвет доходит до Telegram.
 */
class ButtonStyleTest {

    private static final long CHAT = 42L;

    private TrackerService service;
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(TrackerService.class);
        handler = new CommandHandler(service, mock(ReminderSettingsService.class), mock(StatsService.class));
    }

    private static ReplyButton byData(BotReply reply, String callbackData) {
        return reply.buttons().stream().filter(b -> b.callbackData().equals(callbackData)).findFirst()
                .orElseThrow(() -> new AssertionError("нет кнопки " + callbackData + " в " + reply.buttons()));
    }

    @Test
    void removeAllButtonIsRedAndLoud() {
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(1, "А"), new TaskItem(2, "Б")));

        ReplyButton button = byData(handler.handle(CHAT, "/remove"), "rmall");

        assertEquals("danger", button.style());
        assertEquals("⚠️ УДАЛИТЬ ВСЕ", button.text());
    }

    @Test
    void doneAllButtonIsGreenAndLoud() {
        when(service.todayItems(CHAT)).thenReturn(List.of(
                new TodayItem(1, "А", false), new TodayItem(2, "Б", false)));

        ReplyButton button = byData(handler.handle(CHAT, "/today"), "doneall");

        assertEquals("success", button.style());
        assertEquals("✅ ВЫПОЛНИТЬ ВСЕ", button.text());
    }

    @Test
    void deleteConfirmationMakesDeleteRedAndCancelBlue() {
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(1, "А"), new TaskItem(2, "Б")));

        BotReply confirm = handler.handleCallback(CHAT, "rmall").view();

        assertEquals("danger", byData(confirm, "rmall:yes").style());
        assertEquals("primary", byData(confirm, "rmall:no").style()); // безопасный выбор заметнее
    }

    @Test
    void ordinaryButtonsHaveNoStyleSoOnlyBulkActionsStandOut() {
        when(service.todayItems(CHAT)).thenReturn(List.of(
                new TodayItem(10, "А", false), new TodayItem(11, "Б", false)));
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(1, "А"), new TaskItem(2, "Б")));

        List<ReplyButton> all = Stream.of(handler.handle(CHAT, "/today"), handler.handle(CHAT, "/remove"),
                        handler.handle(CHAT, "/menu"))
                .flatMap(r -> r.buttons().stream()).toList();

        List<String> styled = all.stream().filter(b -> b.style() != null).map(ReplyButton::callbackData).toList();
        assertEquals(List.of("doneall", "rmall"), styled);
        assertNull(byData(handler.handle(CHAT, "/today"), "done:10").style());
        assertNull(byData(handler.handle(CHAT, "/today"), "menu").style());
    }

    @Test
    void styleReachesTelegramButtonAndMissingStyleIsNotSent() {
        BotReply reply = new BotReply("т", List.of(
                new ReplyButton("Красная", "a", ReplyButton.DANGER),
                new ReplyButton("Обычная", "b")));

        InlineKeyboardMarkup markup = NotificatorBot.toMarkup(reply);

        InlineKeyboardButton red = markup.getKeyboard().get(0).get(0);
        InlineKeyboardButton plain = markup.getKeyboard().get(1).get(0);
        assertEquals("danger", red.getStyle());
        assertNull(plain.getStyle());
    }

    @Test
    void styleIsSerializedIntoJsonSentToTelegram() throws Exception {
        BotReply reply = new BotReply("т", List.of(
                new ReplyButton("Красная", "a", ReplyButton.DANGER),
                new ReplyButton("Обычная", "b")));

        // та же библиотека сериализует запросы к Telegram через Jackson
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(NotificatorBot.toMarkup(reply));

        assertTrue(json.contains("\"style\":\"danger\""), json);
        assertEquals(1, json.split("\"style\"", -1).length - 1, "поле style должно быть только у красной кнопки: " + json);
    }

    @Test
    void styleConstantsMatchTelegramBotApiValues() {
        assertEquals("danger", ReplyButton.DANGER);
        assertEquals("success", ReplyButton.SUCCESS);
        assertEquals("primary", ReplyButton.PRIMARY);
        assertTrue(new ReplyButton("т", "d").style() == null);
    }
}
