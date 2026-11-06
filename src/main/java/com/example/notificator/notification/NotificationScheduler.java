package com.example.notificator.notification;

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
        for (long userId : service.userIds()) {
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
        if (n.text() != null && !notifier.send(n.chatId(), n.text())) {
            return; // не отправилось — слот не отмечаем, повторим в следующую минуту
        }
        service.markHandled(n.userId(), n.kind(), n.slot());
        log.info("Уведомление {} для пользователя {} {}", n.kind(), n.userId(),
                n.text() == null ? "пропущено (слать нечего)" : "отправлено");
    }
}
