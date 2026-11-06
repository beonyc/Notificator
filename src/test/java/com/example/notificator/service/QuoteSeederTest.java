package com.example.notificator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.notificator.model.Quote;
import com.example.notificator.repository.QuoteRepository;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

/** Тесты загрузки цитат из quotes.txt. */
@DataJpaTest
@Import(QuoteSeeder.class)
class QuoteSeederTest {

    @Autowired
    private QuoteSeeder seeder;
    @Autowired
    private QuoteRepository quotes;

    @Test
    void parsesLinesSkippingCommentsBlanksAndBrokenLines() throws IOException {
        List<Quote> parsed = QuoteSeeder.parse(new StringReader("""
                # комментарий

                Текст с | чертой внутри | Автор
                Строка без автора |
                Строка без разделителя
                Просто текст | Другой автор
                """));

        assertEquals(2, parsed.size());
        assertEquals("Текст с | чертой внутри", parsed.get(0).getText());
        assertEquals("Автор", parsed.get(0).getAuthor());
        assertEquals("Просто текст", parsed.get(1).getText());
    }

    @Test
    void bundledQuotesFileIsValid() throws IOException {
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(QuoteSeeder.RESOURCE).getInputStream(), StandardCharsets.UTF_8)) {
            List<Quote> parsed = QuoteSeeder.parse(reader);

            // 3 напоминания в день, цитата не чаще раза в неделю — для этого нужен запас цитат
            assertTrue(parsed.size() >= 25, "в стартовом наборе должно быть не меньше 25 цитат, а их " + parsed.size());
            assertEquals(parsed.size(), new HashSet<>(parsed.stream().map(Quote::getText).toList()).size(),
                    "цитаты не должны повторяться");
            assertTrue(parsed.stream().allMatch(q -> q.getText().length() <= 500));
        }
    }

    @Test
    void seedLoadsQuotesOnceAndKeepsUsageTime() {
        seeder.seed();
        long count = quotes.count();
        assertTrue(count >= 25);

        Quote used = quotes.findAll().get(0);
        used.setLastUsedAt(Instant.parse("2026-11-01T00:00:00Z"));
        quotes.saveAndFlush(used);

        seeder.seed(); // повторный запуск не плодит дубли и не сбрасывает время показа

        assertEquals(count, quotes.count());
        assertNotNull(quotes.findById(used.getId()).orElseThrow().getLastUsedAt());
    }
}
