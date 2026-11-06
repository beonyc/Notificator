package com.example.notificator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.notificator.model.Quote;
import com.example.notificator.repository.QuoteRepository;
import com.example.notificator.support.MutableClock;
import com.example.notificator.support.NotificationTestConfig;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/** Тесты выбора цитаты. Часы тестовые: «сейчас» = 2026-11-07 05:00 UTC, повтор запрещён 7 дней. */
@DataJpaTest
@Import({NotificationTestConfig.class, QuoteService.class})
class QuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T05:00:00Z");

    @Autowired
    private QuoteService service;
    @Autowired
    private QuoteRepository quotes;
    @Autowired
    private MutableClock clock;

    @Test
    void returnsEmptyWhenNoQuotes() {
        assertTrue(service.pickQuote().isEmpty());
    }

    @Test
    void picksQuoteAndRemembersUsageTime() {
        quotes.save(new Quote("Единственная", "Автор"));

        Quote picked = service.pickQuote().orElseThrow();

        assertEquals("Единственная", picked.getText());
        assertEquals(NOW, picked.getLastUsedAt());
    }

    @Test
    void doesNotRepeatQuoteUsedWithinLastWeek() {
        Quote recent = quotes.save(quote("Недавняя", NOW.minusSeconds(6 * 24 * 3600)));
        Quote old = quotes.save(quote("Давняя", NOW.minusSeconds(8 * 24 * 3600)));
        Quote never = quotes.save(new Quote("Ни разу", "Автор"));

        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            picked.add(service.pickQuote().orElseThrow().getText());
            // возвращаем «давние» в исходное состояние, чтобы выбор повторялся
            old.setLastUsedAt(NOW.minusSeconds(8 * 24 * 3600));
            never.setLastUsedAt(null);
        }

        assertTrue(picked.contains("Давняя") || picked.contains("Ни разу"));
        assertTrue(!picked.contains("Недавняя"), "цитату, показанную менее недели назад, повторять нельзя");
        assertEquals(NOW.minusSeconds(6 * 24 * 3600), recent.getLastUsedAt());
    }

    @Test
    void usesQuoteAgainAfterAWeek() {
        quotes.save(quote("Единственная", NOW.minusSeconds(7 * 24 * 3600 + 1)));

        assertEquals("Единственная", service.pickQuote().orElseThrow().getText());
    }

    @Test
    void whenAllQuotesAreRecentTakesTheLeastRecentlyUsed() {
        quotes.save(quote("Вчера", NOW.minusSeconds(24 * 3600)));
        quotes.save(quote("Три дня назад", NOW.minusSeconds(3 * 24 * 3600)));
        quotes.save(quote("Час назад", NOW.minusSeconds(3600)));

        assertEquals("Три дня назад", service.pickQuote().orElseThrow().getText());
    }

    @Test
    void consecutiveCallsGiveDifferentQuotesWhileFreshOnesRemain() {
        quotes.save(new Quote("Раз", "А"));
        quotes.save(new Quote("Два", "Б"));
        quotes.save(new Quote("Три", "В"));

        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            Quote q = service.pickQuote().orElseThrow();
            assertNotNull(q.getLastUsedAt());
            picked.add(q.getText());
        }

        assertEquals(3, picked.size());
        assertEquals(NOW, clock.instant());
    }

    private static Quote quote(String text, Instant lastUsed) {
        Quote q = new Quote(text, "Автор");
        q.setLastUsedAt(lastUsed);
        return q;
    }
}
