package io.github.actforever.kuudra.api.context;

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
import java.util.Optional;
import java.util.UUID;

/** Execution scopes supplied to adapters and processors. Snapshot maps are stable for one invocation. */
public record EventContext(String flowId, SessionReference session,
                           Map<String, Object> sessionValues, SessionContext sessionContext,
                           Map<String, Object> flowValues, FlowContext flowContext,
                           CancellationToken cancellationToken,
                           Map<String, Object> globalValues, GlobalContext globalContext,
                           Map<String, Object> configuration) {
    public EventContext {
        sessionValues = Map.copyOf(sessionValues); flowValues = Map.copyOf(flowValues);
        globalValues = Map.copyOf(globalValues); configuration = Map.copyOf(configuration);
    }
    public EventContext(String flowId, SessionReference session, Map<String, Object> sessionValues,
                        SessionContext sessionContext, CancellationToken cancellationToken,
                        Map<String, Object> globalValues, Map<String, Object> configuration) {
        this(flowId, session, sessionValues, sessionContext, Map.of(), null, cancellationToken, globalValues, null, configuration);
    }
    public EventContext(String flowId, SessionReference session, Map<String, Object> sessionValues,
                        SessionContext sessionContext, CancellationToken cancellationToken) {
        this(flowId, session, sessionValues, sessionContext, cancellationToken, Map.of(), Map.of());
    }
    public Optional<UUID> sessionId() { return session == null ? Optional.empty() : Optional.of(session.id()); }
    public TypedValueMap configurationValues() { return TypedValueMap.of(configuration); }
    public <T> T configuration(String key, Class<T> type) {
        return TypedValueMap.get(configuration, key, type);
    }
    public <T> T configuration(String key, Class<T> type, T fallback) {
        return TypedValueMap.getOrDefault(configuration, key, type, fallback);
    }
}
