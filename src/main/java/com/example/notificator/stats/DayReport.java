package com.example.notificator.stats;

import com.example.notificator.service.TodayItem;
import java.time.LocalDate;
import java.util.List;

/**
 * Список дел за один день и их статус.
 *
 * @param date   запрошенный день
 * @param status можно ли показывать этот день
 * @param items  дела дня в порядке добавления (пусто, если статус не OK или в этот день дел не было)
 * @param today    true, если это сегодняшний день (он ещё не закончен, невыполненное — ещё не провал)
 * @param editable true, если это вчерашний день: его статусы ещё можно исправить (не успел сегодня)
 */
public record DayReport(LocalDate date, Status status, List<TodayItem> items, boolean today, boolean editable) {

    /** Результат запроса дня. */
    public enum Status {
        /** День можно показывать. */
        OK,
        /** День этой недели, но ещё не наступил. */
        FUTURE,
        /** День не относится к текущей неделе (пн–вс). */
        OUT_OF_WEEK
    }
}
