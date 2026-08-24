package io.github.actforever.kuudra.api;

import java.util.UUID;

public record SessionSnapshot(UUID id, String flowId, long flowRevision, String ingressId, String groupKey,
                              SessionStatus status, boolean cancellationRequested, int activeLeases) { }
