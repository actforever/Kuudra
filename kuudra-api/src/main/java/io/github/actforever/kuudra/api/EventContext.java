package io.github.actforever.kuudra.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Context supplied to adapters and processors; session values are available only for bound Events. */
public record EventContext(String flowId, SessionReference session, Map<String, Object> sessionValues,
                           SessionContext sessionContext, CancellationToken cancellationToken,
                           Map<String, Object> globalValues, Map<String, Object> configuration) {
    public EventContext { sessionValues = Map.copyOf(sessionValues); globalValues = Map.copyOf(globalValues); configuration = Map.copyOf(configuration); }
    public EventContext(String flowId, SessionReference session, Map<String, Object> sessionValues,
                        SessionContext sessionContext, CancellationToken cancellationToken) {
        this(flowId, session, sessionValues, sessionContext, cancellationToken, Map.of(), Map.of());
    }
    public Optional<UUID> sessionId() { return session == null ? Optional.empty() : Optional.of(session.id()); }
}
