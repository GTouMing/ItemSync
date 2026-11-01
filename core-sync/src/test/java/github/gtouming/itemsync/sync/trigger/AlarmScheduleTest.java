package github.gtouming.itemsync.sync.trigger;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlarmScheduleTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void firesTodayWhenTimeIsAhead() {
        AlarmSchedule schedule = new AlarmSchedule(Set.of(DayOfWeek.MONDAY),
                LocalTime.of(23, 0), SHANGHAI);
        Instant after = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, SHANGHAI).toInstant();

        Instant next = schedule.nextFire(after);

        assertEquals(ZonedDateTime.of(2024, 1, 1, 23, 0, 0, 0, SHANGHAI).toInstant(), next);
    }

    @Test
    void firesNextWeekWhenTimeHasPassed() {
        AlarmSchedule schedule = new AlarmSchedule(Set.of(DayOfWeek.MONDAY),
                LocalTime.of(23, 0), SHANGHAI);
        Instant after = ZonedDateTime.of(2024, 1, 1, 23, 30, 0, 0, SHANGHAI).toInstant();

        Instant next = schedule.nextFire(after);

        assertEquals(ZonedDateTime.of(2024, 1, 8, 23, 0, 0, 0, SHANGHAI).toInstant(), next);
    }

    @Test
    void picksTheNextMatchingDay() {
        AlarmSchedule schedule = new AlarmSchedule(
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), LocalTime.of(23, 0), SHANGHAI);
        Instant after = ZonedDateTime.of(2024, 1, 1, 23, 30, 0, 0, SHANGHAI).toInstant();

        Instant next = schedule.nextFire(after);

        assertEquals(ZonedDateTime.of(2024, 1, 3, 23, 0, 0, 0, SHANGHAI).toInstant(), next);
    }

    @Test
    void respectsTheConfiguredZone() {
        AlarmSchedule schedule = new AlarmSchedule(Set.of(DayOfWeek.MONDAY),
                LocalTime.of(23, 0), SHANGHAI);
        Instant after = Instant.parse("2024-01-01T00:00:00Z");

        Instant next = schedule.nextFire(after);

        assertEquals(Instant.parse("2024-01-01T15:00:00Z"), next);
    }
}
