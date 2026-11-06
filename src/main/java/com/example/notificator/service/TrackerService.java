package com.example.notificator.service;

import com.example.notificator.model.Task;
import com.example.notificator.model.TaskEntry;
import com.example.notificator.model.User;
import com.example.notificator.repository.TaskEntryRepository;
import com.example.notificator.repository.TaskRepository;
import com.example.notificator.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Бизнес-логика трекера: регистрация, добавление дел, список на сегодня.
 * Бот ничего не знает про БД — он вызывает этот сервис (так логику проще тестировать и менять).
 *
 * {@code @Service} — компонент слоя бизнес-логики.
 * {@code @Transactional} — каждый метод выполняется в одной транзакции БД: либо все изменения
 * сохраняются, либо (при ошибке) откатываются. Также внутри транзакции работают ленивые связи
 * (task.getTitle() у отметки).
 */
@Service
@Transactional
public class TrackerService {

    private final UserRepository users;
    private final TaskRepository tasks;
    private final TaskEntryRepository entries;
    private final Clock clock;

    public TrackerService(UserRepository users, TaskRepository tasks,
                          TaskEntryRepository entries, Clock clock) {
        this.users = users;
        this.tasks = tasks;
        this.entries = entries;
        this.clock = clock;
    }

    /**
     * Регистрирует пользователя по chatId (команда /start).
     *
     * @return true, если пользователь создан сейчас; false, если уже был зарегистрирован
     */
    public boolean register(long chatId) {
        if (users.findByTelegramChatId(chatId).isPresent()) {
            return false;
        }
        users.save(new User(chatId));
        return true;
    }

    /**
     * Добавляет дело (команда /add) и сразу создаёт отметку на сегодня, чтобы дело появилось в /today.
     * Планировщик создания отметок появится позже (шаг 7), он не будет создавать дубликаты.
     */
    public void addTask(long chatId, String title) {
        User user = findUser(chatId);
        Task task = tasks.save(new Task(user, title));
        entries.save(new TaskEntry(task, today(user)));
    }

    /**
     * Список дел на сегодня (команда /today). Для активных дел, у которых ещё нет отметки
     * на сегодня, отметка создаётся — так дело, добавленное вчера, попадёт в сегодняшний список.
     */
    public List<TodayItem> todayItems(long chatId) {
        User user = findUser(chatId);
        LocalDate today = today(user);

        ensureTodayEntries(user, today);
        return entries.findByTaskUserAndDate(user, today).stream()
                .filter(e -> e.getTask().isActive()) // выполненное, но потом удалённое дело не показываем
                .sorted(Comparator.comparing(TaskEntry::getId)) // порядок добавления дел
                .map(e -> new TodayItem(e.getId(), e.getTask().getTitle(), e.isDone()))
                .toList();
    }

    /**
     * Отмечает дело выполненным (нажатие кнопки «✅»).
     *
     * Проверки важны, потому что callback-данные приходят от клиента и их можно подделать:
     * отметка должна принадлежать этому пользователю и относиться к сегодняшнему дню
     * (по его часовому поясу), иначе вчерашнее сообщение с кнопкой «задним числом» исправило бы статистику.
     * Изменение сущности внутри транзакции Hibernate сохранит сам при её завершении.
     */
    public MarkResult markDone(long chatId, long entryId) {
        User user = findUser(chatId);
        TaskEntry entry = entries.findById(entryId)
                .filter(e -> e.getTask().getUser().getId().equals(user.getId()))
                .orElse(null);
        if (entry == null) {
            return MarkResult.NOT_FOUND;
        }
        if (!entry.getDate().equals(today(user))) {
            return MarkResult.EXPIRED;
        }
        if (entry.isDone()) {
            return MarkResult.ALREADY_DONE;
        }
        entry.markDone();
        return MarkResult.DONE;
    }

    /**
     * Отмечает выполненными все невыполненные дела на сегодня (кнопка «Выполнить все»).
     *
     * @return сколько дел отмечено; 0, если все уже были выполнены или дел нет
     */
    public int markAllDone(long chatId) {
        User user = findUser(chatId);
        LocalDate today = today(user);

        ensureTodayEntries(user, today);
        List<TaskEntry> notDone = entries.findByTaskUserAndDateAndDoneFalse(user, today).stream()
                .filter(e -> e.getTask().isActive())
                .toList();
        notDone.forEach(TaskEntry::markDone);
        return notDone.size();
    }

    /**
     * Удаляет все активные дела пользователя (кнопка «Удалить все»). Правила те же, что у
     * {@link #removeTask}: мягкое удаление, невыполненные отметки на сегодня стираются,
     * выполненные остаются в истории.
     *
     * @return сколько дел удалено
     */
    public int removeAllTasks(long chatId) {
        User user = findUser(chatId);
        LocalDate today = today(user);

        List<Task> active = tasks.findByUserAndActiveTrue(user);
        active.forEach(task -> deactivate(task, today));
        return active.size();
    }

    /** Активные дела пользователя в порядке добавления (список для /remove). */
    public List<TaskItem> activeTasks(long chatId) {
        return tasks.findByUserAndActiveTrue(findUser(chatId)).stream()
                .sorted(Comparator.comparing(Task::getId))
                .map(t -> new TaskItem(t.getId(), t.getTitle()))
                .toList();
    }

    /**
     * Удаляет дело (команда /remove). Это «мягкое удаление»: дело становится неактивным и больше
     * не попадает в списки и новые дни, но строка и выполненные отметки остаются в БД —
     * чтобы не искажалась статистика за прошлые дни. Невыполненная отметка на сегодня удаляется,
     * иначе удалённое дело считалось бы проваленным в итоге дня.
     * Дело должно принадлежать пользователю (callback-данные можно подделать).
     *
     * @return название удалённого дела; пусто, если дело не найдено, чужое или уже удалено
     */
    public Optional<String> removeTask(long chatId, long taskId) {
        User user = findUser(chatId);
        return tasks.findById(taskId)
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .filter(Task::isActive)
                .map(task -> {
                    deactivate(task, today(user));
                    return task.getTitle();
                });
    }

    /** Мягко удаляет дело: делает неактивным и стирает его невыполненную отметку за сегодня. */
    private void deactivate(Task task, LocalDate today) {
        task.setActive(false);
        entries.deleteByTaskAndDateAndDoneFalse(task, today);
    }

    /** Создаёт отметки на сегодня для активных дел, у которых их ещё нет. */
    private void ensureTodayEntries(User user, LocalDate today) {
        for (Task task : tasks.findByUserAndActiveTrue(user)) {
            if (entries.findByTaskAndDate(task, today).isEmpty()) {
                entries.save(new TaskEntry(task, today));
            }
        }
    }

    private User findUser(long chatId) {
        return users.findByTelegramChatId(chatId)
                .orElseThrow(() -> new UserNotRegisteredException(chatId));
    }

    /** «Сегодня» считается по часовому поясу пользователя, а не сервера. */
    private LocalDate today(User user) {
        return LocalDate.now(clock.withZone(ZoneId.of(user.getTimezone())));
    }
}
