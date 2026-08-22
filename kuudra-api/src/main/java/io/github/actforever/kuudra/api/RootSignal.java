package io.github.actforever.kuudra.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A request to create a session for one Flow. */
public record RootSignal(UUID id, RawSignal raw, String flowId, SessionSpec sessionSpec, Instant occurredAt) {
    public RootSignal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(raw, "raw");
        if (flowId == null || flowId.isBlank()) throw new IllegalArgumentException("flowId must not be blank");
        Objects.requireNonNull(sessionSpec, "sessionSpec");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static RootSignal of(RawSignal raw, String flowId, SessionSpec sessionSpec) {
        return new RootSignal(UUID.randomUUID(), raw, flowId, sessionSpec, Instant.now());
    }
}
