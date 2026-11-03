package github.gtouming.itemsync.domain.model;

import github.gtouming.itemsync.domain.identity.GroupId;
import github.gtouming.itemsync.domain.identity.TriggerId;

import java.time.Instant;
import java.util.Objects;

public record Trigger(TriggerId id, GroupId groupId, TriggerType type,
                      String configJson, boolean enabled, Instant nextFireAt) {

    public Trigger {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(configJson, "configJson");
    }
}
