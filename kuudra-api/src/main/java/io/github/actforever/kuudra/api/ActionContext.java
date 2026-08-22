package io.github.actforever.kuudra.api;

import java.util.Map;
import java.util.UUID;

public record ActionContext(UUID sessionId, String flowId, Map<String, Object> sessionValues,
                            CancellationToken cancellationToken) {
    public ActionContext { sessionValues = Map.copyOf(sessionValues); }
}
