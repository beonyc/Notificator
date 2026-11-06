package com.example.notificator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /**
     * Дата (по времени пользователя), за которую уже обработан утренний итог.
     * Нужна, чтобы итог не отправлялся дважды за день, в том числе после перезапуска приложения.
     */
    private LocalDate lastSummaryDate;

    /** Начало последнего обработанного слота напоминаний (по времени пользователя), например 13:00 сегодня. */
    private LocalDateTime lastReminderSlot;

    /**
     * Свои времена напоминаний через запятую («09:00,14:30»); null — используются времена по умолчанию
     * из application.yaml. Хранится строкой, потому что список короткий и отдельная таблица не нужна.
     */
    private String reminderTimes;

    /**
     * Включены ли напоминания; null или true — включены. Тип Boolean (а не boolean), чтобы колонку
     * можно было добавить в уже существующую БД: у старых строк значение будет null, то есть «включено».
     */
    private Boolean remindersEnabled;

    /** Напоминания включены (null у старых записей тоже означает «включены»). */
    public boolean areRemindersEnabled() {
        return !Boolean.FALSE.equals(remindersEnabled);
    }

    public User(Long telegramChatId) {
        this.telegramChatId = telegramChatId;
    }
}
