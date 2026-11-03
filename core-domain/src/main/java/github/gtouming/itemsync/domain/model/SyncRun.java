package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.error.ErrorCode;
import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.identity.TriggerId;

import java.time.Instant;
import java.util.Objects;

public record SyncRun(SyncRunId id, GroupId groupId, TriggerId triggerId,
                      NodeId sourceNodeId, SyncRunState state, Instant startedAt, Instant endedAt,
                      String statsJson, ErrorCode errorCode) {

    public SyncRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
