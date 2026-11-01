package github.gtouming.itemsync.cli;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

record ProcessDiff(Set<String> started, Set<String> stopped) {

    ProcessDiff {
        started = Set.copyOf(Objects.requireNonNull(started, "started"));
        stopped = Set.copyOf(Objects.requireNonNull(stopped, "stopped"));
    }

    static ProcessDiff between(Set<String> previous, Set<String> current) {
        Set<String> started = new LinkedHashSet<>(current);
        started.removeAll(previous);
        Set<String> stopped = new LinkedHashSet<>(previous);
        stopped.removeAll(current);
        return new ProcessDiff(started, stopped);
    }
}
