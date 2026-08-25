package io.github.actforever.kuudra.api.action;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

import java.util.Map;
import java.util.UUID;

public record ActionContext(UUID sessionId, String flowId,
                            Map<String, Object> sessionValues, SessionContext sessionContext,
                            Map<String, Object> flowValues, FlowContext flowContext,
                            ExecutionControl executionControl, EventEmitter emitter,
                            Map<String, Object> globalValues, GlobalContext globalContext,
                            Map<String, Object> configuration) {
    public ActionContext {
        sessionValues = Map.copyOf(sessionValues); flowValues = Map.copyOf(flowValues);
        globalValues = Map.copyOf(globalValues); configuration = Map.copyOf(configuration);
        java.util.Objects.requireNonNull(emitter, "emitter");
    }
    public ActionContext(UUID sessionId, String flowId, Map<String, Object> sessionValues,
                         SessionContext sessionContext, ExecutionControl executionControl, EventEmitter emitter,
                         Map<String, Object> globalValues, Map<String, Object> configuration) {
        this(sessionId, flowId, sessionValues, sessionContext, Map.of(), null, executionControl, emitter, globalValues, null, configuration);
    }
    public ActionContext(UUID sessionId, String flowId, Map<String, Object> sessionValues,
                         SessionContext sessionContext, ExecutionControl executionControl, EventEmitter emitter) {
        this(sessionId, flowId, sessionValues, sessionContext, executionControl, emitter, Map.of(), Map.of());
    }

    /** Emits a derived Event immediately. The Runtime supplies the current Session and lineage. */
    public boolean emit(KuudraEvent event) { return emitter.emit(event); }
    /** @deprecated use {@link #executionControl()}. */
    @Deprecated(forRemoval = false)
    public ExecutionControl cancellationToken() { return executionControl; }
    public TypedValueMap configurationValues() { return TypedValueMap.of(configuration); }
    public <T> T configuration(String key, Class<T> type) {
        return TypedValueMap.get(configuration, key, type);
    }
    public <T> T configuration(String key, Class<T> type, T fallback) {
        return TypedValueMap.getOrDefault(configuration, key, type, fallback);
    }
}
