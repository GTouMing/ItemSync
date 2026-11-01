package github.gtouming.itemsync.domain.replication;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.NodeId;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class VersionVector {

    private static final Comparator<NodeId> BY_VALUE = Comparator.comparing(NodeId::value);
    private static final VersionVector EMPTY = new VersionVector(Compat.map());

    private final NavigableMap<NodeId, Long> counters;

    private VersionVector(Map<NodeId, Long> counters) {
        TreeMap<NodeId, Long> sorted = new TreeMap<>(BY_VALUE);
        sorted.putAll(counters);
        this.counters = Collections.unmodifiableNavigableMap(sorted);
    }

    public static VersionVector empty() {
        return EMPTY;
    }

    public static VersionVector of(Map<NodeId, Long> counters) {
        Objects.requireNonNull(counters, "counters");
        Map<NodeId, Long> validated = new HashMap<>();
        counters.forEach((node, counter) -> {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(counter, "counter");
            if (counter <= 0) {
                throw new IllegalArgumentException(
                        "counter for " + node + " must be positive, got " + counter);
            }
            validated.put(node, counter);
        });
        return validated.isEmpty() ? EMPTY : new VersionVector(validated);
    }

    public static VersionVector counter(NodeId node, long counter) {
        Objects.requireNonNull(node, "node");
        return of(Compat.map(node, counter));
    }

    public long counterOf(NodeId node) {
        Objects.requireNonNull(node, "node");
        Long counter = counters.get(node);
        return counter == null ? 0L : counter;
    }

    public Set<NodeId> nodes() {
        return counters.keySet();
    }

    public boolean isEmpty() {
        return counters.isEmpty();
    }

    public VersionVector increment(NodeId node) {
        Objects.requireNonNull(node, "node");
        Map<NodeId, Long> next = new HashMap<>(counters);
        next.put(node, Math.incrementExact(counterOf(node)));
        return new VersionVector(next);
    }

    public VersionVector merge(VersionVector other) {
        Objects.requireNonNull(other, "other");
        if (counters.isEmpty()) {
            return other;
        }
        if (other.counters.isEmpty()) {
            return this;
        }
        Map<NodeId, Long> next = new HashMap<>(counters);
        other.counters.forEach((node, counter) -> next.merge(node, counter, Math::max));
        return new VersionVector(next);
    }

    public VersionOrder compare(VersionVector other) {
        Objects.requireNonNull(other, "other");
        Set<NodeId> all = new HashSet<>(counters.keySet());
        all.addAll(other.counters.keySet());

        boolean thisAhead = false;
        boolean otherAhead = false;
        for (NodeId node : all) {
            long mine = counterOf(node);
            long theirs = other.counterOf(node);
            if (mine > theirs) {
                thisAhead = true;
            } else if (mine < theirs) {
                otherAhead = true;
            }
            if (thisAhead && otherAhead) {
                return VersionOrder.CONCURRENT;
            }
        }
        if (thisAhead) {
            return VersionOrder.AFTER;
        }
        if (otherAhead) {
            return VersionOrder.BEFORE;
        }
        return VersionOrder.EQUAL;
    }

    public boolean dominates(VersionVector other) {
        return compare(other) == VersionOrder.AFTER;
    }

    public boolean isConcurrentWith(VersionVector other) {
        return compare(other) == VersionOrder.CONCURRENT;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VersionVector that && counters.equals(that.counters);
    }

    @Override
    public int hashCode() {
        return counters.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<NodeId, Long> entry : counters.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            out.append(entry.getKey().value()).append('=').append(entry.getValue());
            first = false;
        }
        return out.append('}').toString();
    }
}
