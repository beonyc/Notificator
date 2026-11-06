package com.example.notificator.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Тестовые часы, время которых можно переставлять прямо в тесте: так проверяем, что происходит
 * в 07:59, 08:00, 13:20 и т.д., не дожидаясь настоящего времени.
 */
public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    /** Переставляет часы на указанный момент (в UTC, например «2026-11-07T05:00:00Z»). */
    public void set(String isoInstant) {
        this.instant = Instant.parse(isoInstant);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
