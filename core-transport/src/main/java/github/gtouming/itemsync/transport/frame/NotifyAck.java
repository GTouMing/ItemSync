package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.SyncRunId;

import java.util.Objects;

public record NotifyAck(SyncRunId runId, boolean accepted, int queuePosition) {

    public NotifyAck {
        Objects.requireNonNull(runId, "runId");
    }
}
