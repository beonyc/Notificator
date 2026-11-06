package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/** Проверяет раскладку кнопок: сколько кнопок в ряду и как собирается клавиатура Telegram. */
class ButtonLayoutTest {

    private static List<ReplyButton> buttons(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new ReplyButton("Кнопка " + i, "data" + i)).toList();
    }

    private static List<Integer> rowSizes(InlineKeyboardMarkup markup) {
        return markup.getKeyboard().stream().map(row -> row.size()).toList();
    }

    @Test
    void oneColumnPutsEachButtonOnItsOwnRow() {
        InlineKeyboardMarkup markup = NotificatorBot.toMarkup(new BotReply("т", buttons(3)));

        assertEquals(List.of(1, 1, 1), rowSizes(markup));
    }

    @Test
    void twoColumnsPutTwoButtonsPerRowAndLastRowMayBeShorter() {
        InlineKeyboardMarkup markup = NotificatorBot.toMarkup(new BotReply("т", buttons(5), 2));

        assertEquals(List.of(2, 2, 1), rowSizes(markup));
        assertEquals("data1", markup.getKeyboard().get(0).get(0).getCallbackData());
        assertEquals("data2", markup.getKeyboard().get(0).get(1).getCallbackData());
        assertEquals("data5", markup.getKeyboard().get(2).get(0).getCallbackData());
    }

    @Test
    void evenNumberOfButtonsFillsAllRows() {
        assertEquals(List.of(2, 2), rowSizes(NotificatorBot.toMarkup(new BotReply("т", buttons(4), 2))));
    }

    @Test
    void emptyButtonsGiveEmptyKeyboardWhichRemovesButtonsOnEdit() {
        assertTrue(NotificatorBot.toMarkup(BotReply.text("т")).getKeyboard().isEmpty());
    }

    @Test
    void invalidColumnCountIsTreatedAsOne() {
        assertEquals(List.of(1, 1), rowSizes(NotificatorBot.toMarkup(new BotReply("т", buttons(2), 0))));
        assertEquals(List.of(1, 1), rowSizes(NotificatorBot.toMarkup(new BotReply("т", buttons(2), -3))));
    }

    @Test
    void plusButtonAppendsAtTheEndAndKeepsColumns() {
        BotReply reply = new BotReply("т", buttons(2), 2).plusButton(new ReplyButton("В меню", "menu"));

        assertEquals(3, reply.buttons().size());
        assertEquals("menu", reply.buttons().get(2).callbackData());
        assertEquals(2, reply.columns());
        assertEquals(2, new BotReply("т", buttons(2)).buttons().size()); // исходный ответ не меняется
    }

    @Test
    void mainMenuIsSentToTelegramTwoButtonsPerRow() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(1L, "/start")).thenReturn(new BotReply("меню", buttons(5), 2));
        NotificatorBot bot = new NotificatorBot("token", client, handler);

        Message message = new Message();
        message.setText("/start");
        message.setChat(Chat.builder().id(1L).type("private").build());
        Update update = new Update();
        update.setMessage(message);
        bot.consume(update);

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(sent.capture());
        assertEquals(List.of(2, 2, 1), rowSizes((InlineKeyboardMarkup) sent.getValue().getReplyMarkup()));
    }
}
