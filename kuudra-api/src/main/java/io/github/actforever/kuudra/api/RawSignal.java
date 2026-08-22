package io.github.actforever.kuudra.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A fact emitted by an input pipeline before a session exists. */
public record RawSignal(UUID id, String type, Instant occurredAt, Map<String, Object> payload) {
    public RawSignal {
        Objects.requireNonNull(id, "id");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(payload, "payload")));
    }

    public static RawSignal of(String type, Map<String, Object> payload) {
        return new RawSignal(UUID.randomUUID(), type, Instant.now(), payload);
    }
}
