package github.gtouming.itemsync.sync.engine;

import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.model.ManifestEntry;
import github.gtouming.itemsync.domain.replication.VersionVector;
import github.gtouming.itemsync.sync.apply.LocalApplier;
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

    public SyncEngine(LocalReconciler reconciler, LocalApplier applier, SyncPlanner planner) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.applier = Objects.requireNonNull(applier, "applier");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public SyncRunResult pullOnce(RemotePeer remote) throws IOException {
        Objects.requireNonNull(remote, "remote");

        Map<LogicalPath, ManifestEntry> local = new LinkedHashMap<>();
        for (ManifestEntry entry : reconciler.reconcile()) {
            local.put(entry.path(), entry);
        }

        List<ManifestEntry> remoteEntries = remote.manifest(reconciler.rootId(), VersionVector.empty());
        List<SyncAction> actions = planner.plan(local, remoteEntries);

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

        return new SyncRunResult(pulled, deleted, conflicts, pendingPush);
    }
}
