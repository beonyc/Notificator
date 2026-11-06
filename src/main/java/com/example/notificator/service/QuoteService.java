package com.example.notificator.service;

import com.example.notificator.config.NotificationProperties;
import com.example.notificator.model.Quote;
import com.example.notificator.repository.QuoteRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Выбор случайной цитаты с защитой от повторов.
 *
 * Правило: цитата не показывается чаще, чем раз в N дней (notifications.quote-repeat-days).
 * Если все цитаты показаны недавно (цитат меньше, чем напоминаний), берём ту, что не показывали
 * дольше всех: лучше повторить самую «старую», чем остаться без цитаты.
 *
 * Генератор случайных чисел передаётся снаружи, чтобы в тестах выбор был предсказуемым.
 */
@Service
@Transactional
public class QuoteService {

    private final QuoteRepository quotes;
    private final Clock clock;
    private final NotificationProperties props;
    private final RandomGenerator random;

    public QuoteService(QuoteRepository quotes, Clock clock, NotificationProperties props,
                        RandomGenerator random) {
        this.quotes = quotes;
        this.clock = clock;
        this.props = props;
        this.random = random;
    }

    /**
     * Выбирает цитату и запоминает время показа.
     *
     * @return цитата; пусто, если в БД нет ни одной цитаты
     */
    public Optional<Quote> pickQuote() {
        List<Quote> all = quotes.findAll();
        if (all.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Instant threshold = now.minus(Duration.ofDays(props.quoteRepeatDays()));

        List<Quote> fresh = all.stream()
                .filter(q -> q.getLastUsedAt() == null || q.getLastUsedAt().isBefore(threshold))
                .toList();

        Quote chosen = fresh.isEmpty()
                ? all.stream().min(Comparator.comparing(Quote::getLastUsedAt)).orElseThrow()
                : fresh.get(random.nextInt(fresh.size()));
        chosen.setLastUsedAt(now); // сущность управляется Hibernate — изменение сохранится при коммите
        return Optional.of(chosen);
    }
}
