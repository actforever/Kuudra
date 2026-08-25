package io.github.actforever.kuudra.api;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

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

    @Test
    void typedValueMapCentralizesConversionAndDefaults() {
        TypedValueMap values = TypedValueMap.of(Map.of("intervalMillis", 25, "enabled", true));
        assertEquals(25L, values.get("intervalMillis", Long.class));
        assertTrue(values.get("enabled", Boolean.class));
        assertEquals(1000L, values.getOrDefault("missing", Long.class, 1000L));
        assertThrows(IllegalArgumentException.class, () -> values.get("missing", String.class));
    }
}
