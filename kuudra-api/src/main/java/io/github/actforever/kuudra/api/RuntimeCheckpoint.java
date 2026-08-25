package io.github.actforever.kuudra.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Consistent in-process Runtime checkpoint captured only after the execution barrier is closed. */
public record RuntimeCheckpoint(Instant capturedAt, int queuedTasks,
                                List<FlowSnapshot> flows, List<SessionSnapshot> sessions,
                                Map<String, Object> globalContext,
                                Map<String, Map<String, Object>> flowContexts) {
    public RuntimeCheckpoint {
        flows = List.copyOf(flows); sessions = List.copyOf(sessions);
        globalContext = Map.copyOf(globalContext);
        flowContexts = flowContexts.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }
}
