package github.gtouming.itemsync.sync.engine;

import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRootId;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.store.metadata.MetadataStore;
import github.gtouming.itemsync.sync.apply.LocalApplier;
import github.gtouming.itemsync.sync.engine.transport.ManifestSnapshot;
import github.gtouming.itemsync.sync.plan.SyncAction;
import github.gtouming.itemsync.sync.plan.SyncPlanner;
import github.gtouming.itemsync.sync.state.LocalReconciler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SyncEngine {

    private final LocalReconciler reconciler;
    private final LocalApplier applier;
    private final SyncPlanner planner;
    private final MetadataStore.CursorRepository cursors;

    public SyncEngine(LocalReconciler reconciler, LocalApplier applier, SyncPlanner planner) {
        this(reconciler, applier, planner, NoopCursorRepository.INSTANCE);
    }

    public SyncEngine(LocalReconciler reconciler, LocalApplier applier, SyncPlanner planner,
                      MetadataStore.CursorRepository cursors) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.applier = Objects.requireNonNull(applier, "applier");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    public SyncRunResult pullOnce(RemotePeer remote) throws IOException {
        Objects.requireNonNull(remote, "remote");

        Map<LogicalPath, ManifestEntry> local = new LinkedHashMap<>();
        for (ManifestEntry entry : reconciler.reconcile()) {
            local.put(entry.path(), entry);
        }

        SyncRootId rootId = reconciler.rootId();
        long since = cursors.pullSequence(rootId, remote.nodeId());
        ManifestSnapshot snapshot = remote.manifest(rootId, since);
        boolean partial = since > 0 && snapshot.sequence() >= since;
        if (since > 0 && snapshot.sequence() < since) {
            snapshot = remote.manifest(rootId, 0);
            partial = false;
        }

        List<SyncAction> actions = partial
                ? planner.planPartial(local, snapshot.entries())
                : planner.plan(local, snapshot.entries());

        int pulled = 0;
        int deleted = 0;
        int conflicts = 0;
        int pendingPush = 0;

        for (SyncAction action : actions) {
            if (action instanceof SyncAction.Pull pull) {
                applier.applyRemote(pull.remote(), remote::openBlob, remote.nodeId());
                pulled++;
            } else if (action instanceof SyncAction.DeleteLocal delete) {
                applier.applyRemote(delete.remote(), remote::openBlob, remote.nodeId());
                deleted++;
            } else if (action instanceof SyncAction.Conflict conflict) {
                applier.resolveConflict(conflict.local(), conflict.remote(), remote::openBlob, remote.nodeId());
                conflicts++;
            } else if (action instanceof SyncAction.Push || action instanceof SyncAction.DeleteRemote) {
                pendingPush++;
            }
        }

        cursors.updatePull(rootId, remote.nodeId(), snapshot.sequence());
        return new SyncRunResult(pulled, deleted, conflicts, pendingPush);
    }

    private static final class NoopCursorRepository implements MetadataStore.CursorRepository {

        private static final NoopCursorRepository INSTANCE = new NoopCursorRepository();

        @Override
        public long pullSequence(SyncRootId rootId, NodeId peerNodeId) {
            return 0L;
        }

        @Override
        public void updatePull(SyncRootId rootId, NodeId peerNodeId, long sequence) {
        }

        @Override
        public long pushSequence(SyncRootId rootId, NodeId peerNodeId) {
            return 0L;
        }

        @Override
        public void updatePush(SyncRootId rootId, NodeId peerNodeId, long sequence) {
        }
    }
}
