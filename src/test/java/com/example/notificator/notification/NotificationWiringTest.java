package com.example.notificator.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.example.notificator.config.NotificationProperties;
import com.example.notificator.repository.QuoteRepository;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Проверяет сборку всего приложения (полный контекст Spring): настройки из application.yaml
 * читаются, цитаты загружаются при старте, сервис уведомлений работает с настоящей БД.
 * Telegram отключён.
 */
@SpringBootTest(properties = {"telegram.enabled=false", "spring.datasource.url=jdbc:h2:mem:wiring",
        "logging.file.name=build/test-logs/wiring.log"})
class NotificationWiringTest {

    @Autowired
    private NotificationProperties props;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private QuoteRepository quotes;

    @Test
    void propertiesAreReadFromApplicationYaml() {
        assertEquals(LocalTime.of(8, 0), props.summaryTime());
        assertEquals(LocalTime.of(0, 5), props.weeklyReportTime());
        assertEquals(List.of(LocalTime.of(13, 0), LocalTime.of(17, 0), LocalTime.of(21, 0)), props.reminderTimes());
        assertEquals(30, props.catchUpMinutes());
        assertEquals(7, props.quoteRepeatDays());
    }

    @Test
    void quotesAreLoadedOnStartup() {
        assertTrue(quotes.count() >= 25);
    }

    @Test
    void schedulerTickWorksOnRealServiceAndDatabase() {
        // В тестовом контексте Telegram выключен, поэтому планировщик (он включается только при
        // telegram.enabled=true) собираем вручную: настоящий сервис и БД, отправка — заглушка.
        NotificationScheduler scheduler = new NotificationScheduler(notificationService, mock(Notifier.class));

        scheduler.tick(); // пользователей нет — просто не должно быть ошибок
    }
}
