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
    void wrappersMakeExecutionDomainExplicitAndPreserveCausalLineage() {
        UUID sessionId = UUID.randomUUID();
        KuudraEvent event = KuudraEvent.of("handler.output", EventData.empty());
        SessionEventWrapper bound = new SessionEventWrapper(event, new SessionReference(sessionId, "flow"));
        KuudraEvent exported = event.withLineage(event.lineage().descendFrom(event, bound.session()));
        RawEventWrapper raw = new RawEventWrapper(exported);
        assertEquals(EventDomain.SESSION, bound.domain()); assertEquals(EventDomain.RAW, raw.domain());
        assertTrue(raw.event().lineage().parentSessionIds().contains(sessionId));
    }
}
