package github.gtouming.itemsync.sync.trigger;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Set;

public record AlarmSchedule(Set<DayOfWeek> days, LocalTime time, ZoneId zone) {

    public AlarmSchedule {
        Objects.requireNonNull(days, "days");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(zone, "zone");
        if (days.isEmpty()) {
            throw new IllegalArgumentException("alarm schedule needs at least one day");
        }
        days = Set.copyOf(days);
    }

    public Instant nextFire(Instant after) {
        Objects.requireNonNull(after, "after");
        ZonedDateTime local = after.atZone(zone);
        for (int offset = 0; offset <= 8; offset++) {
            LocalDate date = local.toLocalDate().plusDays(offset);
            if (!days.contains(date.getDayOfWeek())) {
                continue;
            }
            ZonedDateTime candidate = date.atTime(time).atZone(zone);
            if (candidate.toInstant().isAfter(after)) {
                return candidate.toInstant();
            }
        }
        throw new IllegalStateException("no matching day within 8 days");
    }
}
