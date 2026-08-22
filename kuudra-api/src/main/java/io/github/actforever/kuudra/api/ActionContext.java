package io.github.actforever.kuudra.api;

import java.util.UUID;

public record ActionContext(UUID sessionId, String flowId, CancellationToken cancellationToken) {
}
