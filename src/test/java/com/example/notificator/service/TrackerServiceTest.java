package com.example.notificator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.notificator.model.Task;
import com.example.notificator.model.TaskEntry;
import com.example.notificator.model.User;
import com.example.notificator.repository.TaskEntryRepository;
import com.example.notificator.repository.TaskRepository;
import com.example.notificator.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Тесты сервиса на настоящей БД (в памяти). Часы зафиксированы на 21:30 UTC 6 ноября —
 * в Москве (UTC+3) это уже 00:30 7 ноября, поэтому тест заодно проверяет,
 * что «сегодня» считается по часовому поясу пользователя, а не сервера.
 */
@DataJpaTest
@Import({TrackerService.class, TrackerServiceTest.FixedClockConfig.class})
class TrackerServiceTest {

    private static final LocalDate MOSCOW_TODAY = LocalDate.of(2026, 11, 7);
    private static final long CHAT = 100L;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-06T21:30:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private TrackerService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private TaskEntryRepository entries;

    @Test
    void registerCreatesUserOnlyOnce() {
        assertTrue(service.register(CHAT));
        assertFalse(service.register(CHAT));

        assertEquals(1, users.count());
    }

    @Test
    void addTaskCreatesTaskAndTodayEntryInUserTimezone() {
        service.register(CHAT);

        service.addTask(CHAT, "Читать");

        User user = users.findByTelegramChatId(CHAT).orElseThrow();
        Task task = tasks.findByUserAndActiveTrue(user).get(0);
        assertEquals("Читать", task.getTitle());
        assertTrue(entries.findByTaskAndDate(task, MOSCOW_TODAY).isPresent());
    }

    @Test
    void todayItemsReturnsTasksInOrderWithStatus() {
        service.register(CHAT);
        service.addTask(CHAT, "Читать");
        service.addTask(CHAT, "Зарядка");
        User user = users.findByTelegramChatId(CHAT).orElseThrow();
        TaskEntry first = entries.findByTaskUserAndDate(user, MOSCOW_TODAY).stream()
                .filter(e -> e.getTask().getTitle().equals("Читать")).findFirst().orElseThrow();
        first.markDone();

        List<TodayItem> items = service.todayItems(CHAT);

        assertEquals(List.of("Читать", "Зарядка"), items.stream().map(TodayItem::title).toList());
        assertEquals(List.of(true, false), items.stream().map(TodayItem::done).toList());
    }

    @Test
    void todayItemsCreatesMissingEntryForOldActiveTaskWithoutDuplicates() {
        service.register(CHAT);
        User user = users.findByTelegramChatId(CHAT).orElseThrow();
        tasks.save(new Task(user, "Добавлено вчера")); // отметки на сегодня нет

        assertEquals("Добавлено вчера", service.todayItems(CHAT).get(0).title());
        assertEquals(1, service.todayItems(CHAT).size()); // повторный вызов не плодит дубликаты
    }

    @Test
    void markDoneSetsDoneAndTimeThenReportsAlreadyDone() {
        service.register(CHAT);
        service.addTask(CHAT, "Читать");
        long entryId = service.todayItems(CHAT).get(0).entryId();

        assertEquals(MarkResult.DONE, service.markDone(CHAT, entryId));

        TaskEntry entry = entries.findById(entryId).orElseThrow();
        assertTrue(entry.isDone());
        assertNotNull(entry.getDoneAt());
        assertTrue(service.todayItems(CHAT).get(0).done());
        assertEquals(MarkResult.ALREADY_DONE, service.markDone(CHAT, entryId));
    }

    @Test
    void markDoneRejectsEntryOfAnotherUser() {
        service.register(CHAT);
        service.register(CHAT + 1);
        service.addTask(CHAT, "Чужое дело");
        long entryId = service.todayItems(CHAT).get(0).entryId();

        assertEquals(MarkResult.NOT_FOUND, service.markDone(CHAT + 1, entryId));
        assertFalse(entries.findById(entryId).orElseThrow().isDone());
    }

    @Test
    void markDoneRejectsUnknownEntry() {
        service.register(CHAT);

        assertEquals(MarkResult.NOT_FOUND, service.markDone(CHAT, 987654L));
    }

    @Test
    void markDoneRejectsEntryFromPreviousDay() {
        service.register(CHAT);
        User user = users.findByTelegramChatId(CHAT).orElseThrow();
        Task task = tasks.save(new Task(user, "Вчерашнее"));
        TaskEntry yesterday = entries.save(new TaskEntry(task, MOSCOW_TODAY.minusDays(1)));

        assertEquals(MarkResult.EXPIRED, service.markDone(CHAT, yesterday.getId()));
        assertFalse(yesterday.isDone());
    }

    @Test
    void removeTaskDeactivatesItAndDropsTodaysUndoneEntry() {
        service.register(CHAT);
        service.addTask(CHAT, "Читать");
        service.addTask(CHAT, "Зарядка");
        long readId = service.activeTasks(CHAT).get(0).taskId();

        assertEquals(Optional.of("Читать"), service.removeTask(CHAT, readId));

        assertEquals(List.of("Зарядка"), service.activeTasks(CHAT).stream().map(TaskItem::title).toList());
        assertEquals(List.of("Зарядка"), service.todayItems(CHAT).stream().map(TodayItem::title).toList());
        assertFalse(tasks.findById(readId).orElseThrow().isActive()); // строка осталась в БД
        assertEquals(1, entries.count());
    }

    @Test
    void removedTaskKeepsDoneEntryInHistoryButIsHiddenFromToday() {
        service.register(CHAT);
        service.addTask(CHAT, "Читать");
        long entryId = service.todayItems(CHAT).get(0).entryId();
        service.markDone(CHAT, entryId);
        long taskId = service.activeTasks(CHAT).get(0).taskId();

        service.removeTask(CHAT, taskId);

        assertTrue(entries.findById(entryId).orElseThrow().isDone());
        assertTrue(service.todayItems(CHAT).isEmpty());
    }

    @Test
    void removeTaskRejectsOtherUsersAndAlreadyRemovedTasks() {
        service.register(CHAT);
        service.register(CHAT + 1);
        service.addTask(CHAT, "Чужое дело");
        long taskId = service.activeTasks(CHAT).get(0).taskId();

        assertTrue(service.removeTask(CHAT + 1, taskId).isEmpty());
        assertEquals(1, service.activeTasks(CHAT).size());
        assertTrue(service.removeTask(CHAT, taskId).isPresent());
        assertTrue(service.removeTask(CHAT, taskId).isEmpty()); // повторно — уже удалено
        assertTrue(service.removeTask(CHAT, 987654L).isEmpty());
    }

    @Test
    void unregisteredUserCannotRemoveOrListTasks() {
        assertThrows(UserNotRegisteredException.class, () -> service.removeTask(CHAT, 1L));
        assertThrows(UserNotRegisteredException.class, () -> service.activeTasks(CHAT));
    }

    @Test
    void unregisteredUserCannotMarkDone() {
        assertThrows(UserNotRegisteredException.class, () -> service.markDone(CHAT, 1L));
    }

    @Test
    void inactiveTaskIsNotShownToday() {
        service.register(CHAT);
        User user = users.findByTelegramChatId(CHAT).orElseThrow();
        Task removed = new Task(user, "Удалённое");
        removed.setActive(false);
        tasks.save(removed);

        assertTrue(service.todayItems(CHAT).isEmpty());
    }

    @Test
    void unregisteredUserCannotAddOrListTasks() {
        assertThrows(UserNotRegisteredException.class, () -> service.addTask(CHAT, "Читать"));
        assertThrows(UserNotRegisteredException.class, () -> service.todayItems(CHAT));
    }
}
