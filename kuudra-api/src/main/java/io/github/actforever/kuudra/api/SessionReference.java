package io.github.actforever.kuudra.api;

import java.util.UUID;

/** Immutable identity of the active execution Session carried by an Event. */
public record SessionReference(UUID id, String flowId) {
    public SessionReference {
        if (id == null) throw new IllegalArgumentException("session id must not be null");
        if (flowId == null || flowId.isBlank()) throw new IllegalArgumentException("flowId must not be blank");
    }
}
