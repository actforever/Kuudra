package io.github.actforever.kuudra.api;

import java.util.Map;
import java.util.UUID;

public record ActionContext(UUID sessionId, String flowId, Map<String, Object> sessionValues,
                            SessionContext sessionContext, CancellationToken cancellationToken, EventEmitter emitter) {
    public ActionContext { sessionValues = Map.copyOf(sessionValues); java.util.Objects.requireNonNull(emitter, "emitter"); }

    /** Emits a derived Event immediately. The Runtime supplies the current Session and lineage. */
    public boolean emit(Event event) { return emitter.emit(event); }
}
