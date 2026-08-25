package io.github.actforever.kuudra.api.runtime;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

import java.util.Optional;
import java.util.UUID;

/** Read-only, in-memory state used for admission decisions. */
public interface RuntimeStateView {
    boolean hasActiveSession(String flowId, String sessionName);
    int activeSessionCount(String flowId, String sessionName);
    Optional<SessionSnapshot> session(UUID sessionId);
    Optional<FlowSnapshot> flow(String flowId);
}
