package com.example.notificator.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.notificator.model.Task;
import com.example.notificator.model.TaskEntry;
import com.example.notificator.model.User;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Тесты репозиториев на настоящей БД. {@code @DataJpaTest} поднимает только слой JPA и
 * подменяет БД на встроенную в памяти (данные из ./data не затрагиваются); каждый тест
 * выполняется в транзакции, которая откатывается, поэтому тесты не влияют друг на друга.
 * Бот в этом тесте не запускается (поднимается только слой JPA).
 */
@DataJpaTest
class RepositoriesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

    @Autowired
    private UserRepository users;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private TaskEntryRepository entries;

    private User user;
    private Task task;

    @BeforeEach
    void setUp() {
        user = users.save(new User(111L));
        task = tasks.save(new Task(user, "Читать 30 минут"));
    }

    @Test
    void userGetsDefaultsAndIsFoundByChatId() {
        User found = users.findByTelegramChatId(111L).orElseThrow();

        assertEquals("Europe/Moscow", found.getTimezone());
        assertNotNull(found.getCreatedAt());
        assertTrue(users.findByTelegramChatId(999L).isEmpty());
    }

    @Test
    void chatIdMustBeUnique() {
        assertThrows(DataIntegrityViolationException.class,
                () -> users.saveAndFlush(new User(111L)));
    }

    @Test
    void inactiveTasksAreNotReturned() {
        Task removed = tasks.save(new Task(user, "Бросить курить"));
        removed.setActive(false);
        tasks.save(removed);

        List<Task> active = tasks.findByUserAndActiveTrue(user);

        assertEquals(1, active.size());
        assertEquals("Читать 30 минут", active.get(0).getTitle());
    }

    @Test
    void entriesAreFoundByUserAndDateAndDoneFlag() {
        Task second = tasks.save(new Task(user, "Зарядка"));
        entries.save(new TaskEntry(task, DAY));
        TaskEntry done = new TaskEntry(second, DAY);
        done.markDone();
        entries.save(done);

        assertEquals(2, entries.findByTaskUserAndDate(user, DAY).size());
        List<TaskEntry> notDone = entries.findByTaskUserAndDateAndDoneFalse(user, DAY);
        assertEquals(1, notDone.size());
        assertEquals("Читать 30 минут", notDone.get(0).getTask().getTitle());
        assertNotNull(done.getDoneAt());
    }

    @Test
    void entriesAreFoundForPeriod() {
        entries.save(new TaskEntry(task, DAY));
        entries.save(new TaskEntry(task, DAY.plusDays(3)));
        entries.save(new TaskEntry(task, DAY.plusDays(10)));

        assertEquals(2, entries.findByTaskUserAndDateBetween(user, DAY, DAY.plusDays(6)).size());
    }

    @Test
    void secondEntryForSameTaskAndDayIsRejected() {
        entries.save(new TaskEntry(task, DAY));

        assertTrue(entries.findByTaskAndDate(task, DAY).isPresent());
        assertThrows(DataIntegrityViolationException.class,
                () -> entries.saveAndFlush(new TaskEntry(task, DAY)));
    }
}
