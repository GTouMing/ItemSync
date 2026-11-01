package github.gtouming.itemsync.domain.replication;

import github.gtouming.itemsync.domain.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionVectorTest {

    private static final NodeId A = NodeId.of("node-a");
    private static final NodeId B = NodeId.of("node-b");
    private static final NodeId C = NodeId.of("node-c");

    @Test
    void emptyVectorsAreEqual() {
        assertEquals(VersionOrder.EQUAL, VersionVector.empty().compare(VersionVector.empty()));
    }

    @Test
    void missingNodeCountsAsZero() {
        assertEquals(0L, VersionVector.empty().counterOf(A));
    }

    @Test
    void incrementReturnsNewVectorWithoutMutatingOriginal() {
        VersionVector original = VersionVector.empty();
        VersionVector next = original.increment(A);

        assertEquals(1L, next.counterOf(A));
        assertEquals(0L, original.counterOf(A));
        assertEquals(VersionOrder.BEFORE, original.compare(next));
    }

    @Test
    void vectorWithHigherCounterIsAfter() {
        VersionVector before = VersionVector.counter(A, 1);
        VersionVector after = VersionVector.counter(A, 2);

        assertEquals(VersionOrder.AFTER, after.compare(before));
        assertEquals(VersionOrder.BEFORE, before.compare(after));
        assertTrue(after.dominates(before));
        assertFalse(before.dominates(after));
    }

    @Test
    void vectorWithAdditionalNodeIsAfter() {
        VersionVector base = VersionVector.counter(A, 1);
        VersionVector extended = base.increment(A).increment(B);

        assertEquals(VersionOrder.AFTER, extended.compare(base));
    }

    @Test
    void independentIncrementsAreConcurrent() {
        VersionVector left = VersionVector.counter(A, 1);
        VersionVector right = VersionVector.counter(B, 1);

        assertEquals(VersionOrder.CONCURRENT, left.compare(right));
        assertTrue(left.isConcurrentWith(right));
        assertTrue(right.isConcurrentWith(left));
    }

    @Test
    void divergentCountersAreConcurrent() {
        VersionVector left = VersionVector.of(Map.of(A, 2L, B, 1L));
        VersionVector right = VersionVector.of(Map.of(A, 1L, B, 2L));

        assertEquals(VersionOrder.CONCURRENT, left.compare(right));
    }

    @Test
    void identicalVectorsAreEqual() {
        VersionVector left = VersionVector.of(Map.of(A, 3L, B, 2L));
        VersionVector right = VersionVector.of(Map.of(B, 2L, A, 3L));

        assertEquals(VersionOrder.EQUAL, left.compare(right));
        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void mergeTakesMaximumPerNode() {
        VersionVector left = VersionVector.of(Map.of(A, 2L, B, 1L));
        VersionVector right = VersionVector.of(Map.of(A, 1L, B, 3L));

        VersionVector merged = left.merge(right);

        assertEquals(2L, merged.counterOf(A));
        assertEquals(3L, merged.counterOf(B));
        assertEquals(VersionOrder.AFTER, merged.compare(left));
        assertEquals(VersionOrder.AFTER, merged.compare(right));
    }

    @Test
    void mergeWithEmptyVectorReturnsOther() {
        VersionVector vector = VersionVector.counter(A, 1);

        assertEquals(vector, VersionVector.empty().merge(vector));
        assertEquals(vector, vector.merge(VersionVector.empty()));
    }

    @Test
    void rejectsNonPositiveCounters() {
        assertThrows(IllegalArgumentException.class, () -> VersionVector.counter(A, 0));
        assertThrows(IllegalArgumentException.class, () -> VersionVector.counter(A, -1));
    }

    @Test
    void nodesAreOrderedDeterministically() {
        VersionVector vector = VersionVector.of(Map.of(C, 1L, A, 2L, B, 3L));

        assertEquals(List.of(A, B, C), List.copyOf(vector.nodes()));
    }

    @Test
    void incrementOverflowIsRejected() {
        VersionVector maxed = VersionVector.counter(A, Long.MAX_VALUE);

        assertThrows(ArithmeticException.class, () -> maxed.increment(A));
    }
}
