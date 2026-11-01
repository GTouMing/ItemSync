package github.gtouming.itemsync.cli;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessDiffTest {

    @Test
    void detectsStartedAndStopped() {
        ProcessDiff diff = ProcessDiff.between(Set.of("a", "b"), Set.of("b", "c"));

        assertEquals(Set.of("c"), diff.started());
        assertEquals(Set.of("a"), diff.stopped());
    }

    @Test
    void reportsNothingWhenUnchanged() {
        ProcessDiff diff = ProcessDiff.between(Set.of("a"), Set.of("a"));

        assertEquals(Set.of(), diff.started());
        assertEquals(Set.of(), diff.stopped());
    }
}
