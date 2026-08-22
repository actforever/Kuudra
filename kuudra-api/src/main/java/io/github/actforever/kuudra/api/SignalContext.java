package io.github.actforever.kuudra.api;

import java.util.Map;
import java.util.UUID;

/** Immutable view supplied to adapters, processors and Actors for a single signal. */
public record SignalContext(UUID sessionId, String flowId, Map<String, Object> sessionValues,
                            CancellationToken cancellationToken) {
    public SignalContext { sessionValues = Map.copyOf(sessionValues); }
}
