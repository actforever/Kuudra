package io.github.actforever.kuudra.api;

import java.util.Objects;
import java.util.UUID;

/** A signal admitted into one KuudraFlow and bound to a session. */
public record Signal(RawSignal raw, UUID sessionId, String flowId, long sequence) {
    public Signal {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(sessionId, "sessionId");
        if (flowId == null || flowId.isBlank()) throw new IllegalArgumentException("flowId must not be blank");
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
    }
}
