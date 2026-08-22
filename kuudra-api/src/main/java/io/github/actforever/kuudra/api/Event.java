package io.github.actforever.kuudra.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** The sole business message type in the Kuudra event runtime. */
public record Event(UUID id, String type, Instant occurredAt, EventData data, EventLineage lineage, SessionReference session) {
    public Event {
        Objects.requireNonNull(id, "id");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        Objects.requireNonNull(occurredAt, "occurredAt"); Objects.requireNonNull(data, "data"); Objects.requireNonNull(lineage, "lineage");
    }
    public Event(UUID id, String type, Instant occurredAt, Map<String, Object> data) {
        this(id, type, occurredAt, EventData.fromLegacy(data), EventLineage.origin(), null);
    }
    public static Event of(String type, Map<String, Object> data) { return new Event(UUID.randomUUID(), type, Instant.now(), data); }
    public static Event of(String type, EventData data) { return new Event(UUID.randomUUID(), type, Instant.now(), data, EventLineage.origin(), null); }
    public boolean hasSession() { return session != null; }
    public Event withSession(SessionReference reference) { return new Event(id, type, occurredAt, data, lineage, Objects.requireNonNull(reference, "reference")); }
    public Event withLineage(EventLineage newLineage) { return new Event(id, type, occurredAt, data, Objects.requireNonNull(newLineage, "newLineage"), session); }
    public Event withoutSession() { return session == null ? this : new Event(id, type, occurredAt, data, lineage.descendFrom(this, true), null); }
    public Event retype(String newType) { return new Event(id, newType, occurredAt, data, lineage, session); }
}
