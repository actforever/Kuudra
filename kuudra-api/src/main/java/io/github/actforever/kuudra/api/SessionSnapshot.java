package io.github.actforever.kuudra.api;

import java.util.Set;
import java.util.UUID;

public record SessionSnapshot(UUID id, String flowId, String name, String admissionKey,
                              SessionStatus status, boolean cancellationRequested, Set<UUID> parentSessionIds) {
    public SessionSnapshot { parentSessionIds = Set.copyOf(parentSessionIds); }
    public SessionSnapshot(UUID id, String flowId, String name, String admissionKey, SessionStatus status, boolean cancellationRequested) {
        this(id, flowId, name, admissionKey, status, cancellationRequested, Set.of());
    }
}
