package github.gtouming.itemsync.sync.trigger;

import github.gtouming.itemsync.domain.compat.Compat;

import java.util.Objects;
import java.util.Set;

public sealed interface TriggerConfig {

    record Alarm(AlarmSchedule schedule) implements TriggerConfig {
        public Alarm {
            Objects.requireNonNull(schedule, "schedule");
        }
    }

    record DirectoryChange() implements TriggerConfig {
    }

    record ProcessEvent(Set<String> executables, boolean onStart, boolean onStop)
            implements TriggerConfig {
        public ProcessEvent {
            executables = Compat.copySet(Objects.requireNonNull(executables, "executables"));
            if (executables.isEmpty()) {
                throw new IllegalArgumentException("process trigger needs at least one executable");
            }
            if (!onStart && !onStop) {
                throw new IllegalArgumentException("process trigger needs onStart or onStop");
            }
        }
    }
}
