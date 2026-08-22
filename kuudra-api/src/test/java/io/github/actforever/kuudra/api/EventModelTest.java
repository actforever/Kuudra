package io.github.actforever.kuudra.api;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventModelTest {
    @Test
    void eventDataKeepsPluginNamespacesImmutable() {
        EventData data = EventData.of("input", Map.of("key", "A")).with("gesture", "result", "DOUBLE_A");
        assertEquals("A", data.require("input", "key")); assertEquals("DOUBLE_A", data.require("gesture", "result"));
        assertThrows(UnsupportedOperationException.class, () -> data.namespace("input").put("key", "B"));
    }
    @Test
    void detachingSessionRetainsParentSessionInLineage() {
        UUID sessionId = UUID.randomUUID();
        Event bound = Event.of("actor.output", EventData.empty()).withSession(new SessionReference(sessionId, "flow"));
        Event detached = bound.withoutSession();
        assertFalse(detached.hasSession()); assertTrue(detached.lineage().parentSessionIds().contains(sessionId));
    }
}
