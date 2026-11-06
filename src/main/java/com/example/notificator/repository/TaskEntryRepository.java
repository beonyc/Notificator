package com.example.notificator.repository;

import com.example.notificator.model.Task;
import com.example.notificator.model.TaskEntry;
import com.example.notificator.model.User;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Репозиторий дневных отметок. Запись {@code TaskUser} в имени метода — путь по связям:
 * TaskEntry.task.user (Spring Data сам делает JOIN).
 */
public interface TaskEntryRepository extends JpaRepository<TaskEntry, Long> {

    /** Все отметки пользователя за день — для /today и итога дня. */
    List<TaskEntry> findByTaskUserAndDate(User user, LocalDate date);

    /** Невыполненные отметки пользователя за день — для напоминаний. */
    List<TaskEntry> findByTaskUserAndDateAndDoneFalse(User user, LocalDate date);

    /** Отметки пользователя за период (включительно) — для недельной статистики. */
    List<TaskEntry> findByTaskUserAndDateBetween(User user, LocalDate from, LocalDate to);

    /** Есть ли уже отметка по делу за день (чтобы не создать дубликат). */
    Optional<TaskEntry> findByTaskAndDate(Task task, LocalDate date);
}
