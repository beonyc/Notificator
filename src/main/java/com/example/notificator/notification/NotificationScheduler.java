package com.example.notificator.notification;

import com.example.notificator.util.LogContext;
import com.example.notificator.util.LogText;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Запускает проверку уведомлений каждую минуту и отправляет то, что подошло по расписанию.
 *
 * Почему «каждую минуту», а не отдельный cron на 08:00, 13:00 и т.д.: у каждого пользователя свой
 * часовой пояс, а cron привязан к одной зоне. Проверка раз в минуту дёшева (пользователей мало),
 * а решение «пора ли этому пользователю» принимает {@link NotificationService}.
 *
 * Порядок для надёжности: сначала отправляем, и только после успешной отправки отмечаем слот
 * обработанным. Если отправка не удалась, повторим на следующей минуте (пока слот не устарел).
 */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationService service;
    private final Notifier notifier;

    public NotificationScheduler(NotificationService service, Notifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    /** Cron «0 * * * * *» — в нулевую секунду каждой минуты. */
    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        List<Long> userIds = service.userIds();
        // debug: тик идёт каждую минуту, на уровне INFO он забил бы лог; шаги с действиями пишутся как INFO
        log.debug("Проверка уведомлений: пользователей {}", userIds.size());
        for (long userId : userIds) {
            try {
                service.prepare(userId).ifPresent(this::deliver);
            } catch (RuntimeException e) {
                // Ошибка у одного пользователя (например, неверный часовой пояс) не должна
                // останавливать уведомления остальным.
                log.error("Ошибка при подготовке уведомления для пользователя {}", userId, e);
            }
        }
    }

    private void deliver(PendingNotification n) {
        // номер чата попадает в каждую строку лога этой доставки
        try (var ignored = LogContext.forChat(n.chatId())) {
            log.info("Подошло уведомление {} (слот {}): {}", n.kind(), n.slot(),
                    n.text() == null ? "слать нечего" : LogText.oneLine(n.text()));
            if (n.text() != null && !notifier.send(n.chatId(), n.text())) {
                // не отправилось — слот не отмечаем, повторим в следующую минуту
                log.warn("Уведомление {} не отправлено, повторим в следующую минуту (слот {})", n.kind(), n.slot());
                return;
            }
            service.markHandled(n.userId(), n.kind(), n.slot());
            log.info("Уведомление {} (слот {}) {}, слот отмечен обработанным", n.kind(), n.slot(),
                    n.text() == null ? "пропущено" : "отправлено");
        }
    }
}
