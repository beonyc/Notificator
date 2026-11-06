package com.example.notificator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Пользователь бота. Создаётся по команде /start и однозначно определяется chatId в Telegram.
 *
 * {@code @Entity} — класс отображается на таблицу БД, а объект — на строку таблицы.
 * {@code @Table(name = "users")} — слово USER зарезервировано во многих БД, поэтому таблица users.
 * Lombok {@code @Getter/@Setter} генерирует геттеры и сеттеры. {@code @Data} для сущностей не
 * используем: сгенерированные equals/hashCode ломаются со связями Hibernate.
 * {@code @NoArgsConstructor} — Hibernate требует пустой конструктор.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    /** Первичный ключ; значение выдаёт БД (автоинкремент). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID чата в Telegram. Уникален: один пользователь — одна запись. */
    @Column(nullable = false, unique = true)
    private Long telegramChatId;

    /** Часовой пояс пользователя (нужен планировщикам: напоминания в 08:00 по его времени). */
    @Column(nullable = false)
    private String timezone = "Europe/Moscow";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public User(Long telegramChatId) {
        this.telegramChatId = telegramChatId;
    }
}
