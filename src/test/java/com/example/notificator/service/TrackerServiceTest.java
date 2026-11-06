package com.example.notificator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertEquals(List.of(new TodayItem("Читать", true), new TodayItem("Зарядка", false)),
                service.todayItems(CHAT));
    }

    @Test
    void todayItemsCreatesMissingEntryForOldActiveTaskWithoutDuplicates() {
        service.register(CHAT);
        User user = users.findByTelegramChatId(CHAT).orElseThrow();
        tasks.save(new Task(user, "Добавлено вчера")); // отметки на сегодня нет

        assertEquals(List.of(new TodayItem("Добавлено вчера", false)), service.todayItems(CHAT));
        assertEquals(1, service.todayItems(CHAT).size()); // повторный вызов не плодит дубликаты
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
