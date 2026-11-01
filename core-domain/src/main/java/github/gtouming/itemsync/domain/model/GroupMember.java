package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.NodeId;

import java.time.Instant;
import java.util.Objects;

public record GroupMember(GroupId groupId, NodeId nodeId, String displayName,
                          boolean consent, boolean blocked, Instant joinedAt) {

    public GroupMember {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(nodeId, "nodeId");
    }
}
