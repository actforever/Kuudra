package io.github.actforever.kuudra.api;

import java.util.Optional;
import java.util.UUID;

/** Read-only, in-memory state used for admission decisions. */
public interface RuntimeStateView {
    boolean hasActiveSession(String flowId, String sessionName);
    int activeSessionCount(String flowId, String sessionName);
    Optional<SessionSnapshot> session(UUID sessionId);
}
