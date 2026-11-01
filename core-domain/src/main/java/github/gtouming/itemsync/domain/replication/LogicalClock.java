package github.gtouming.itemsync.domain.replication;

import java.util.Objects;

public final class LogicalClock implements Comparable<LogicalClock> {

    public static final LogicalClock ZERO = new LogicalClock(0L);

    private final long value;

    private LogicalClock(long value) {
        this.value = value;
    }

    public static LogicalClock of(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("logical clock must not be negative, got " + value);
        }
        return value == 0L ? ZERO : new LogicalClock(value);
    }

    public long value() {
        return value;
    }

    public LogicalClock tick() {
        return of(Math.incrementExact(value));
    }

    public LogicalClock observe(long remote) {
        if (remote < 0) {
            throw new IllegalArgumentException("remote clock must not be negative, got " + remote);
        }
        return of(Math.incrementExact(Math.max(value, remote)));
    }

    public LogicalClock observe(LogicalClock remote) {
        Objects.requireNonNull(remote, "remote");
        return observe(remote.value);
    }

    @Override
    public int compareTo(LogicalClock other) {
        return Long.compare(value, other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LogicalClock that && value == that.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
