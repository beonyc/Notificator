package com.example.notificator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.notificator.model.User;
import com.example.notificator.repository.UserRepository;
import com.example.notificator.support.MutableClock;
import com.example.notificator.support.NotificationTestConfig;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/** Тесты личных настроек напоминаний. Часы: 2026-11-07 05:00 UTC = 08:00 в Москве. */
@DataJpaTest
@Import({NotificationTestConfig.class, TrackerService.class, ReminderSettingsService.class})
class ReminderSettingsServiceTest {

    private static final long CHAT = 100L;
    private static final List<LocalTime> DEFAULTS = List.of(LocalTime.of(13, 0), LocalTime.of(17, 0), LocalTime.of(21, 0));

    @Autowired
    private ReminderSettingsService service;
    @Autowired
    private TrackerService tracker;
    @Autowired
    private UserRepository users;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        tracker.register(CHAT);
    }

    @Test
    void newUserGetsEnabledDefaultTimes() {
        ReminderSettings s = service.get(CHAT);

        assertTrue(s.enabled());
        assertFalse(s.custom());
        assertEquals(DEFAULTS, s.times());
        assertEquals("Europe/Moscow", s.timezone());
    }

    @Test
    void setTimesSavesSortedWithoutDuplicatesAndEnables() {
        service.setEnabled(CHAT, false);

        service.setTimes(CHAT, List.of(LocalTime.of(20, 0), LocalTime.of(9, 30), LocalTime.of(20, 0)));

        ReminderSettings s = service.get(CHAT);
        assertTrue(s.enabled());
        assertTrue(s.custom());
        assertEquals(List.of(LocalTime.of(9, 30), LocalTime.of(20, 0)), s.times());
        assertEquals("09:30,20:00", users.findByTelegramChatId(CHAT).orElseThrow().getReminderTimes());
    }

    @Test
    void setTimesRejectsEmptyAndTooLongLists() {
        assertThrows(IllegalArgumentException.class, () -> service.setTimes(CHAT, List.of()));

        List<LocalTime> seven = List.of(LocalTime.of(8, 0), LocalTime.of(9, 0), LocalTime.of(10, 0),
                LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(13, 0), LocalTime.of(14, 0));
        assertThrows(IllegalArgumentException.class, () -> service.setTimes(CHAT, seven));
        assertEquals(DEFAULTS, service.get(CHAT).times()); // ничего не изменилось
    }

    @Test
    void maximumNumberOfRemindersIsAllowed() {
        List<LocalTime> six = List.of(LocalTime.of(8, 0), LocalTime.of(9, 0), LocalTime.of(10, 0),
                LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(13, 0));

        service.setTimes(CHAT, six);

        assertEquals(six, service.get(CHAT).times());
    }

    @Test
    void disablingKeepsCustomTimesAndEnablingRestoresThem() {
        service.setTimes(CHAT, List.of(LocalTime.of(9, 30)));

        service.setEnabled(CHAT, false);
        assertFalse(service.get(CHAT).enabled());
        assertEquals(List.of(LocalTime.of(9, 30)), service.get(CHAT).times());
        assertTrue(service.activeTimes(users.findByTelegramChatId(CHAT).orElseThrow()).isEmpty());

        service.setEnabled(CHAT, true);
        assertTrue(service.get(CHAT).enabled());
        assertEquals(List.of(LocalTime.of(9, 30)), service.activeTimes(users.findByTelegramChatId(CHAT).orElseThrow()));
    }

    @Test
    void resetToDefaultDropsCustomTimesAndEnables() {
        service.setTimes(CHAT, List.of(LocalTime.of(9, 30)));
        service.setEnabled(CHAT, false);

        service.resetToDefault(CHAT);

        ReminderSettings s = service.get(CHAT);
        assertTrue(s.enabled());
        assertFalse(s.custom());
        assertEquals(DEFAULTS, s.times());
        assertNull(users.findByTelegramChatId(CHAT).orElseThrow().getReminderTimes());
    }

    @Test
    void anyChangeMarksAlreadyPassedSlotsHandled() {
        clock.set("2026-11-07T11:40:00Z"); // 14:40 по Москве

        service.setTimes(CHAT, List.of(LocalTime.of(14, 30)));

        assertEquals(LocalDateTime.of(2026, 11, 7, 14, 40),
                users.findByTelegramChatId(CHAT).orElseThrow().getLastReminderSlot());
    }

    @Test
    void userCreatedBeforeThisFeatureHasRemindersEnabled() {
        User old = users.findByTelegramChatId(CHAT).orElseThrow();
        old.setRemindersEnabled(null); // у старых записей в БД колонка пустая

        assertTrue(old.areRemindersEnabled());
        assertTrue(service.get(CHAT).enabled());
    }

    @Test
    void unregisteredUserCannotChangeSettings() {
        assertThrows(UserNotRegisteredException.class, () -> service.get(999L));
        assertThrows(UserNotRegisteredException.class, () -> service.setEnabled(999L, false));
        assertThrows(UserNotRegisteredException.class, () -> service.setTimes(999L, List.of(LocalTime.NOON)));
        assertThrows(UserNotRegisteredException.class, () -> service.resetToDefault(999L));
    }
}
