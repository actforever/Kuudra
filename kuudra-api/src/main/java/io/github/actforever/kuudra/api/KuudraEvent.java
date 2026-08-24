package io.github.actforever.kuudra.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable business event. Execution state is carried by a {@link KuudraEventWrapper}. */
public record KuudraEvent(UUID id, String type, Instant occurredAt, EventData data, EventLineage lineage) {
    public KuudraEvent {
        Objects.requireNonNull(id, "id");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(lineage, "lineage");
    }

    public KuudraEvent(UUID id, String type, Instant occurredAt, Map<String, Object> data) {
        this(id, type, occurredAt, EventData.fromLegacy(data), EventLineage.origin());
    }

    public static KuudraEvent of(String type, Map<String, Object> data) {
        return new KuudraEvent(UUID.randomUUID(), type, Instant.now(), data);
    }

    public static KuudraEvent of(String type, EventData data) {
        return new KuudraEvent(UUID.randomUUID(), type, Instant.now(), data, EventLineage.origin());
    }

    public KuudraEvent retype(String newType) { return new KuudraEvent(id, newType, occurredAt, data, lineage); }
    public KuudraEvent withData(EventData newData) { return new KuudraEvent(id, type, occurredAt, newData, lineage); }
    public KuudraEvent withLineage(EventLineage newLineage) { return new KuudraEvent(id, type, occurredAt, data, newLineage); }
}
