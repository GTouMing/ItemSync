package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.BlobHash;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.model.ItemKind;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.LogicalClock;
import github.gtouming.itemsync.domain.replication.VersionVector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestFramePayloadsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final BlobHash HASH = BlobHash.parse("a".repeat(64));
    private static final NodeId NODE_A = NodeId.of("node-a");
    private static final GroupId GROUP = GroupId.parse("group001");
    private static final SyncRootId ROOT = SyncRootId.of("root-1");
    private static final SyncRunId RUN = SyncRunId.of("run-1");

    @Test
    void roundTripsNotify() {
        Notify notify = new Notify(RUN, GROUP, LogicalClock.of(5), ROOT,
                VersionVector.of(Map.of(NODE_A, 2L)));

        assertEquals(notify, FramePayloads.decodeNotify(FramePayloads.encodeNotify(notify)));
    }

    @Test
    void roundTripsNotifyAck() {
        NotifyAck ack = new NotifyAck(RUN, true, 3);

        assertEquals(ack, FramePayloads.decodeNotifyAck(FramePayloads.encodeNotifyAck(ack)));
    }

    @Test
    void roundTripsManifestRequest() {
        ManifestRequest withHash = new ManifestRequest(ROOT, VersionVector.of(Map.of(NODE_A, 1L)),
                17L, "ignore-hash");
        ManifestRequest withoutHash = new ManifestRequest(ROOT, VersionVector.empty(), null);

        assertEquals(withHash, FramePayloads.decodeManifestRequest(FramePayloads.encodeManifestRequest(withHash)));
        assertEquals(withoutHash,
                FramePayloads.decodeManifestRequest(FramePayloads.encodeManifestRequest(withoutHash)));
    }

    @Test
    void defaultsMissingManifestSequenceFieldsToZero() {
        ManifestRequest request = FramePayloads.decodeManifestRequest(
                "{\"rootId\":\"root-1\",\"since\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ManifestEnd end = FramePayloads.decodeManifestEnd(
                "{\"totalEntries\":3}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(0L, request.sinceSequence());
        assertEquals(0L, end.sequence());
    }

    @Test
    void roundTripsManifestPage() {
        ManifestPage page = new ManifestPage(List.of(
                new ManifestEntry(LogicalPath.of("docs/a.txt"), ItemKind.FILE, HASH, 12, T0,
                        VersionVector.of(Map.of(NODE_A, 2L)), false),
                new ManifestEntry(LogicalPath.of("docs"), ItemKind.DIRECTORY, null, 0, T0,
                        VersionVector.of(Map.of(NODE_A, 1L)), false),
                new ManifestEntry(LogicalPath.of("docs/gone.txt"), ItemKind.FILE, null, 0, T0,
                        VersionVector.of(Map.of(NODE_A, 3L)), true)),
                "docs/gone.txt");

        assertEquals(page, FramePayloads.decodeManifestPage(FramePayloads.encodeManifestPage(page)));
    }

    @Test
    void roundTripsManifestEnd() {
        ManifestEnd end = new ManifestEnd(42, 19L);

        assertEquals(end, FramePayloads.decodeManifestEnd(FramePayloads.encodeManifestEnd(end)));
    }
}
