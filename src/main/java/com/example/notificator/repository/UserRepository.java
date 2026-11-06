package com.example.notificator.repository;

import com.example.notificator.model.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Репозиторий пользователей. Достаточно объявить интерфейс: Spring Data сам создаёт реализацию
 * с готовыми save, findById, findAll, delete и т.д. Методы вида findBy... Spring Data превращает
 * в SQL-запросы по имени метода.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Найти пользователя по chatId в Telegram (нужно для /start и всех остальных команд). */
    Optional<User> findByTelegramChatId(Long telegramChatId);
}
