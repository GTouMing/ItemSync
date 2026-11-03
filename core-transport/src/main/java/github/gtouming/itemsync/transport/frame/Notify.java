package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.SyncRunId;
import github.gtouming.itemsync.domain.replication.LogicalClock;
import github.gtouming.itemsync.domain.replication.VersionVector;

import java.util.Objects;

public record Notify(SyncRunId runId, GroupId groupId, LogicalClock logicalTime,
                     VersionVector vectorSnapshot) {

    public Notify {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(logicalTime, "logicalTime");
        Objects.requireNonNull(vectorSnapshot, "vectorSnapshot");
    }
}
