package io.github.actforever.kuudra.api.event;

import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.session.CurrentSessionControl;

import java.util.UUID;

/** Capabilities supplied to one SESSION-domain Controller handler invocation. */
public interface EventHandlerContext {
    UUID sessionId();
    String abilityId();
    long abilityRevision();
    String nodeId();
    String handlerName();
    SessionContext session();
    AbilityContext ability();
    GlobalContext global();
    TypedValueMap arguments();
    ExecutionControl executionControl();
    CurrentSessionControl sessionControl();
    boolean emit(KuudraEvent event);
}
