package io.github.actforever.kuudra.api;

import java.util.Map;
import java.util.UUID;

public record ActionContext(UUID sessionId, String flowId, Map<String, Object> sessionValues,
                            SessionContext sessionContext, CancellationToken cancellationToken, EventEmitter emitter,
                            Map<String, Object> globalValues, Map<String, Object> configuration) {
    public ActionContext { sessionValues = Map.copyOf(sessionValues); globalValues = Map.copyOf(globalValues); configuration = Map.copyOf(configuration); java.util.Objects.requireNonNull(emitter, "emitter"); }
    public ActionContext(UUID sessionId, String flowId, Map<String, Object> sessionValues,
                         SessionContext sessionContext, CancellationToken cancellationToken, EventEmitter emitter) {
        this(sessionId, flowId, sessionValues, sessionContext, cancellationToken, emitter, Map.of(), Map.of());
    }

    /** Emits a derived Event immediately. The Runtime supplies the current Session and lineage. */
    public boolean emit(Event event) { return emitter.emit(event); }
}
