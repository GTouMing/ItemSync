package github.gtouming.itemsync.sync.plan;

import github.gtouming.itemsync.domain.compat.Compat;

import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.model.ManifestEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class SyncPlanner {

    public SyncAction decide(ManifestEntry local, ManifestEntry remote) {
        if (local == null && remote == null) {
            throw new IllegalArgumentException("at least one entry is required");
        }
        if (local == null) {
            return new SyncAction.Pull(remote);
        }
        if (remote == null) {
            return new SyncAction.Push(local);
        }
        return switch (remote.vector().compare(local.vector())) {
            case AFTER -> remote.deleted()
                    ? new SyncAction.DeleteLocal(remote)
                    : new SyncAction.Pull(remote);
            case BEFORE -> local.deleted()
                    ? new SyncAction.DeleteRemote(local)
                    : new SyncAction.Push(local);
            case EQUAL -> new SyncAction.Skip(local.path());
            case CONCURRENT -> new SyncAction.Conflict(local, remote);
        };
    }

    public List<SyncAction> plan(Map<LogicalPath, ManifestEntry> local,
                                 Collection<ManifestEntry> remoteEntries) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(remoteEntries, "remoteEntries");

        Map<LogicalPath, ManifestEntry> remote = remoteMap(remoteEntries);
        Set<LogicalPath> paths = new TreeSet<>(Comparator.comparing(LogicalPath::value));
        paths.addAll(local.keySet());
        paths.addAll(remote.keySet());

        List<SyncAction> actions = new ArrayList<>();
        for (LogicalPath path : paths) {
            SyncAction action = decide(local.get(path), remote.get(path));
            if (!(action instanceof SyncAction.Skip)) {
                actions.add(action);
            }
        }
        return Compat.copyList(actions);
    }

    public List<SyncAction> planPartial(Map<LogicalPath, ManifestEntry> local,
                                        Collection<ManifestEntry> remoteEntries) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(remoteEntries, "remoteEntries");

        Map<LogicalPath, ManifestEntry> remote = remoteMap(remoteEntries);
        List<SyncAction> actions = new ArrayList<>();
        for (ManifestEntry remoteEntry : remote.values()) {
            SyncAction action = decide(local.get(remoteEntry.path()), remoteEntry);
            if (!(action instanceof SyncAction.Skip)) {
                actions.add(action);
            }
        }
        return Compat.copyList(actions);
    }

    private static Map<LogicalPath, ManifestEntry> remoteMap(
            Collection<ManifestEntry> remoteEntries) {
        Map<LogicalPath, ManifestEntry> remote = new LinkedHashMap<>();
        for (ManifestEntry entry : remoteEntries) {
            if (remote.put(entry.path(), entry) != null) {
                throw new IllegalArgumentException("duplicate remote entry: " + entry.path());
            }
        }
        return remote;
    }
}
