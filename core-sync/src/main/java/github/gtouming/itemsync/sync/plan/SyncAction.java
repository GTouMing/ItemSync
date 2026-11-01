package github.gtouming.itemsync.sync.plan;

import github.gtouming.itemsync.domain.identity.LogicalPath;
import github.gtouming.itemsync.domain.model.ManifestEntry;

public sealed interface SyncAction {

    LogicalPath path();

    record Pull(ManifestEntry remote) implements SyncAction {
        @Override
        public LogicalPath path() {
            return remote.path();
        }
    }

    record Push(ManifestEntry local) implements SyncAction {
        @Override
        public LogicalPath path() {
            return local.path();
        }
    }

    record DeleteLocal(ManifestEntry remote) implements SyncAction {
        @Override
        public LogicalPath path() {
            return remote.path();
        }
    }

    record DeleteRemote(ManifestEntry local) implements SyncAction {
        @Override
        public LogicalPath path() {
            return local.path();
        }
    }

    record Conflict(ManifestEntry local, ManifestEntry remote) implements SyncAction {
        @Override
        public LogicalPath path() {
            return local.path();
        }
    }

    record Skip(LogicalPath path) implements SyncAction {
    }
}
