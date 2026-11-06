package com.example.notificator.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Мотивационная цитата для напоминаний. Стартовый набор загружается из quotes.txt.
 * Поле lastUsedAt хранит, когда цитату показывали в последний раз, — по нему мы
 * не повторяем одну цитату чаще, чем раз в неделю (даже после перезапуска приложения).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Quote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String text;

    @Column(nullable = false)
    private String author;

    /** Когда цитату показывали в последний раз; null — ещё ни разу. */
    private Instant lastUsedAt;

    public Quote(String text, String author) {
        this.text = text;
        this.author = author;
    }
}
