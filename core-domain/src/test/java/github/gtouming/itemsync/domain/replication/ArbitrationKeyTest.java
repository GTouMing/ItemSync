package github.gtouming.itemsync.domain.replication;

import github.gtouming.itemsync.domain.identity.DeviceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArbitrationKeyTest {

    private static final DeviceId A = DeviceId.of("device-a");
    private static final DeviceId B = DeviceId.of("device-b");

    @Test
    void ordersByLogicalTimeFirst() {
        ArbitrationKey earlier = new ArbitrationKey(LogicalClock.of(5), B);
        ArbitrationKey later = new ArbitrationKey(LogicalClock.of(6), A);

        assertTrue(earlier.compareTo(later) < 0);
        assertTrue(later.compareTo(earlier) > 0);
    }

    @Test
    void breaksTiesByDeviceId() {
        ArbitrationKey first = new ArbitrationKey(LogicalClock.of(5), A);
        ArbitrationKey second = new ArbitrationKey(LogicalClock.of(5), B);

        assertTrue(first.compareTo(second) < 0);
        assertTrue(second.compareTo(first) > 0);
    }

    @Test
    void identicalKeysCompareToZero() {
        ArbitrationKey left = new ArbitrationKey(LogicalClock.of(5), A);
        ArbitrationKey right = new ArbitrationKey(LogicalClock.of(5), A);

        assertEquals(0, left.compareTo(right));
        assertEquals(left, right);
    }

    @Test
    void sortsByTimeThenDeviceId() {
        List<ArbitrationKey> keys = new ArrayList<>(List.of(
                new ArbitrationKey(LogicalClock.of(7), A),
                new ArbitrationKey(LogicalClock.of(5), B),
                new ArbitrationKey(LogicalClock.of(5), A),
                new ArbitrationKey(LogicalClock.of(7), B)));
        Collections.shuffle(keys, new Random(42));
        keys.sort(null);

        assertEquals(List.of(
                new ArbitrationKey(LogicalClock.of(5), A),
                new ArbitrationKey(LogicalClock.of(5), B),
                new ArbitrationKey(LogicalClock.of(7), A),
                new ArbitrationKey(LogicalClock.of(7), B)), keys);
    }
}
