package io.github.actforever.kuudra.api.session;

import java.util.UUID;

/** A capability bound to the current Handler invocation; it cannot control any other Session. */
public interface CurrentSessionControl {
    UUID sessionId();

    /** Requests cooperative cancellation. The request is idempotent and Runtime owns the state transition. */
    boolean requestCancellation(String reason);

    static CurrentSessionControl unavailable(UUID sessionId) {
        return new CurrentSessionControl() {
            @Override public UUID sessionId() { return sessionId; }
            @Override public boolean requestCancellation(String reason) { return false; }
        };
    }
}
