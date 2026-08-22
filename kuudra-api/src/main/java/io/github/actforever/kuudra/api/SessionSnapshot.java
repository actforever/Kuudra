package io.github.actforever.kuudra.api;

import java.util.UUID;

public record SessionSnapshot(UUID id, String flowId, String name, String admissionKey,
                              SessionStatus status, boolean cancellationRequested) {
}
