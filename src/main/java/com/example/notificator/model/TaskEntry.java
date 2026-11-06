package com.example.notificator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Отметка по делу за конкретный день: «читал ли я 6 ноября».
 * Task — это «что делать», TaskEntry — «сделано ли сегодня».
 *
 * {@code @UniqueConstraint} — на одно дело в один день может быть только одна отметка;
 * БД не даст создать дубликат, даже если планировщик случайно сработает дважды.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "date"}))
@Getter
@Setter
@NoArgsConstructor
public class TaskEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    /** День отметки (без времени). */
    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private boolean done = false;

    /** Когда дело отмечено выполненным; null, пока не выполнено. */
    private Instant doneAt;

    public TaskEntry(Task task, LocalDate date) {
        this.task = task;
        this.date = date;
    }

    /** Отмечает дело выполненным и запоминает момент отметки. */
    public void markDone() {
        this.done = true;
        this.doneAt = Instant.now();
    }
}
