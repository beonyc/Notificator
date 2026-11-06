package com.example.notificator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Шаблон дела, например «Читать 30 минут». Отметки по конкретным дням хранит {@link TaskEntry}.
 *
 * Поле {@code active} — «мягкое удаление»: по команде /remove дело не стирается из БД,
 * а становится неактивным, чтобы сохранилась история и статистика за прошлые дни.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Владелец дела. {@code @ManyToOne} — много дел у одного пользователя; в БД это колонка
     * user_id (внешний ключ). LAZY — пользователь подгружается из БД только при обращении к нему.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Task(User user, String title) {
        this.user = user;
        this.title = title;
    }
}
