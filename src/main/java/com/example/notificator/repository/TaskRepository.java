package com.example.notificator.repository;

import com.example.notificator.model.Task;
import com.example.notificator.model.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Репозиторий дел (шаблонов). */
public interface TaskRepository extends JpaRepository<Task, Long> {

    /** Активные дела пользователя (не «удалённые» через /remove). */
    List<Task> findByUserAndActiveTrue(User user);

    /** Все активные дела всех пользователей — для ежедневного создания TaskEntry. */
    List<Task> findByActiveTrue();
}
