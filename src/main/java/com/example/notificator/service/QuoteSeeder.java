package com.example.notificator.service;

import com.example.notificator.model.Quote;
import com.example.notificator.repository.QuoteRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * При запуске загружает цитаты из файла quotes.txt в таблицу Quote.
 * Загрузка идемпотентна: добавляются только цитаты, которых в БД ещё нет (сравнение по тексту),
 * поэтому можно дописать новые цитаты в файл — после перезапуска они появятся,
 * а время показа уже существующих не сбросится.
 */
@Component
public class QuoteSeeder {

    private static final Logger log = LoggerFactory.getLogger(QuoteSeeder.class);

    static final String RESOURCE = "quotes.txt";

    private final QuoteRepository quotes;

    public QuoteSeeder(QuoteRepository quotes) {
        this.quotes = quotes;
    }

    /** Выполняется один раз, когда приложение полностью запущено. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8)) {
            Set<String> existing = new HashSet<>();
            quotes.findAll().forEach(q -> existing.add(q.getText()));

            List<Quote> toAdd = parse(reader).stream()
                    .filter(q -> existing.add(q.getText()))
                    .toList();
            quotes.saveAll(toAdd);
            log.info("Цитаты: добавлено {}, всего в БД {}", toAdd.size(), existing.size());
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + RESOURCE, e);
        }
    }

    /**
     * Разбирает файл цитат. Строка: «Текст | Автор». Пустые строки, комментарии (#) и строки
     * без разделителя пропускаются. Делим по ПОСЛЕДНЕМУ символу «|», чтобы он в тексте не мешал.
     */
    static List<Quote> parse(Reader reader) throws IOException {
        List<Quote> result = new ArrayList<>();
        BufferedReader lines = new BufferedReader(reader);
        String line;
        while ((line = lines.readLine()) != null) {
            line = line.strip();
            int sep = line.lastIndexOf('|');
            if (line.isEmpty() || line.startsWith("#") || sep <= 0) {
                continue;
            }
            String text = line.substring(0, sep).strip();
            String author = line.substring(sep + 1).strip();
            if (!text.isEmpty() && !author.isEmpty()) {
                result.add(new Quote(text, author));
            }
        }
        return result;
    }
}
