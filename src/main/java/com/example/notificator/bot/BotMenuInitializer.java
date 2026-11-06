package com.example.notificator.bot;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Регистрирует в Telegram список команд бота. Благодаря этому в чате рядом с полем ввода
 * появляется кнопка «Меню» со списком команд, а при вводе «/» Telegram подсказывает команды.
 *
 * Список хранится на стороне Telegram, поэтому достаточно отправить его при каждом запуске
 * (метод setMyCommands): так меню всегда совпадает с кодом и не нужно настраивать его
 * вручную через @BotFather.
 */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true", matchIfMissing = true)
public class BotMenuInitializer {

    private static final Logger log = LoggerFactory.getLogger(BotMenuInitializer.class);

    /**
     * Команды меню. Имя пишется без «/», описание — до 256 символов.
     * При добавлении новой команды в {@link CommandHandler} нужно добавить её и сюда.
     */
    static final List<BotCommand> COMMANDS = List.of(
            new BotCommand("today", "Дела на сегодня"),
            new BotCommand("add", "Добавить дело: /add Читать 30 минут"),
            new BotCommand("remove", "Убрать дело из списка"),
            new BotCommand("reminders", "Время напоминаний и их отключение"),
            new BotCommand("start", "Начать работу с ботом"));

    private final TelegramClient telegramClient;

    public BotMenuInitializer(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    /**
     * Выполняется один раз, когда приложение полностью запущено ({@link ApplicationReadyEvent}).
     * Ошибку (например, нет интернета) только логируем: без меню бот всё равно работает.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerMenu() {
        try {
            telegramClient.execute(new SetMyCommands(COMMANDS));
            log.info("Меню команд бота зарегистрировано ({} шт.)", COMMANDS.size());
        } catch (TelegramApiException e) {
            log.warn("Не удалось зарегистрировать меню команд: {}", e.getMessage());
        }
    }
}
