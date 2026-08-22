package io.github.actforever.kuudra.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Observation-only runtime event; never re-enters the business signal pipeline. */
public record SystemEvent(UUID id, Instant occurredAt, String type, Map<String, Object> data) {
    public SystemEvent { data = Map.copyOf(data); }
    public static SystemEvent of(String type, Map<String, Object> data) {
        return new SystemEvent(UUID.randomUUID(), Instant.now(), type, data);
    }
}
