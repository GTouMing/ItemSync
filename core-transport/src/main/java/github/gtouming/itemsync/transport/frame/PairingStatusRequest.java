package github.gtouming.itemsync.transport.frame;

import github.gtouming.itemsync.domain.identity.GroupId;

import java.util.Objects;

public record PairingStatusRequest(GroupId groupId) {

    public PairingStatusRequest {
        Objects.requireNonNull(groupId, "groupId");
    }
}
