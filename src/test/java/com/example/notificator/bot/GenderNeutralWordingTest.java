package com.example.notificator.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.notificator.service.ReminderSettings;
import com.example.notificator.support.GenderCheck;
import com.example.notificator.service.ReminderSettingsService;
import com.example.notificator.service.TaskItem;
import com.example.notificator.service.TodayItem;
import com.example.notificator.service.TrackerService;
import com.example.notificator.stats.DayReport;
import com.example.notificator.stats.DayStat;
import com.example.notificator.stats.StatsService;
import com.example.notificator.stats.WeekOverview;
import com.example.notificator.stats.WeeklyStats;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Бот обращается к пользователю, не зная его пола, поэтому в сообщениях не должно быть форм, выдающих мужской род
 * («не успел», «выполнил», «зарегистрирован»…). Тест собирает тексты и подписи кнопок всех экранов и ищет такие формы.
 *
 * Слова самого бота о себе («Не понял») сюда не входят: это его собственная речь, а не обращение к пользователю.
 */
class GenderNeutralWordingTest {

    private static final long CHAT = 42L;

    @Test
    void patternActuallyCatchesTheOldPhrases() {
        // страховка самого теста: старые формулировки он обязан ловить, иначе проверка ничего не стоит
        for (String old : List.of("Не успел вчера?", "Выполнил, но не успел отметить?", "Ты ещё не сделал 2 из 3",
                "Ты уже зарегистрирован", "Красава, день закрыт", "Проленился", "Передумал — нажми")) {
            assertTrue(GenderCheck.MASCULINE.matcher(old).find(), old);
        }
        for (String fine : List.of("Нужно поправить вчерашние отметки?", "Осталось сделать 2 из 3",
                "Регистрация уже пройдена", "Так держать", "Лень победила", "Не понял.", "Вставай и делай")) {
            assertFalse(GenderCheck.MASCULINE.matcher(fine).find(), fine);
        }
    }

    @Test
    void allBotScreensAndButtonsAreGenderNeutral() {
        TrackerService service = mock(TrackerService.class);
        ReminderSettingsService reminders = mock(ReminderSettingsService.class);
        StatsService stats = mock(StatsService.class);
        CommandHandler handler = new CommandHandler(service, reminders, stats);

        LocalDate wed = LocalDate.of(2026, 11, 4);
        LocalDate thu = LocalDate.of(2026, 11, 5);
        when(service.register(CHAT)).thenReturn(true, false);
        when(service.todayItems(CHAT)).thenReturn(List.of(
                new TodayItem(1, "Читать", false), new TodayItem(2, "Зарядка", true), new TodayItem(3, "Сон", false)));
        when(service.activeTasks(CHAT)).thenReturn(List.of(new TaskItem(7, "Читать"), new TaskItem(8, "Зарядка")));
        when(reminders.get(CHAT)).thenReturn(new ReminderSettings(true, List.of(LocalTime.of(9, 0)), true, "Europe/Moscow"));
        when(stats.currentWeek(CHAT)).thenReturn(new WeeklyStats(LocalDate.of(2026, 11, 2), thu,
                0, 0, 0, List.of(), List.of(), null, null, 0, null, 0, "Лень победила"));
        when(stats.weekOverview(CHAT)).thenReturn(new WeekOverview(LocalDate.of(2026, 11, 2), thu,
                List.of(new DayStat(wed, 2, 1), new DayStat(thu, 2, 0))));
        when(stats.dayReport(CHAT, wed)).thenReturn(new DayReport(wed, DayReport.Status.OK,
                List.of(new TodayItem(1, "Читать", true), new TodayItem(2, "Зарядка", false)), false, true));
        when(stats.dayReport(CHAT, thu)).thenReturn(new DayReport(thu, DayReport.Status.OK,
                List.of(new TodayItem(1, "Читать", false)), true, false));

        List<BotReply> screens = new ArrayList<>(List.of(
                handler.handle(CHAT, "/start"), handler.handle(CHAT, "/start"), handler.handle(CHAT, "/menu"),
                handler.handle(CHAT, "привет"), handler.handle(CHAT, "/add"), handler.handle(CHAT, "/add Читать"),
                handler.handle(CHAT, "/today"), handler.handle(CHAT, "/remove"), handler.handle(CHAT, "/reminders"),
                handler.handle(CHAT, "/week"), handler.handle(CHAT, "/cancel"), handler.handle(CHAT, "/reminders 25:00"),
                handler.handle(CHAT, "/add " + "я".repeat(CommandHandler.MAX_TITLE_LENGTH + 1))));
        for (String button : List.of("menu", "add", "today", "remove", "reminders", "week", "days", "rmall", "rem:edit",
                "day:2026-11-04", "day:2026-11-05")) {
            screens.add(handler.handleCallback(CHAT, button).view());
        }
        // ожидание названия дела: слишком длинное название
        handler.handleCallback(CHAT, "add");
        screens.add(handler.handle(CHAT, "я".repeat(CommandHandler.MAX_TITLE_LENGTH + 1)));

        for (BotReply screen : screens) {
            GenderCheck.assertNeutral("текст экрана", screen.text());
            screen.buttons().forEach(b -> GenderCheck.assertNeutral("кнопка «" + b.text() + "»", b.text()));
        }
    }

    @Test
    void toastNoticesAreGenderNeutralToo() {
        TrackerService service = mock(TrackerService.class);
        CommandHandler handler = new CommandHandler(service, mock(ReminderSettingsService.class), mock(StatsService.class));
        when(service.markDone(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(com.example.notificator.service.MarkResult.DONE,
                        com.example.notificator.service.MarkResult.ALREADY_DONE,
                        com.example.notificator.service.MarkResult.EXPIRED,
                        com.example.notificator.service.MarkResult.NOT_FOUND);
        when(service.todayItems(CHAT)).thenReturn(List.of());

        for (int i = 0; i < 4; i++) {
            GenderCheck.assertNeutral("всплывающее уведомление", handler.handleCallback(CHAT, "done:1").notice());
        }
        GenderCheck.assertNeutral("всплывающее уведомление", handler.handleCallback(CHAT, "hack").notice());
    }
}
