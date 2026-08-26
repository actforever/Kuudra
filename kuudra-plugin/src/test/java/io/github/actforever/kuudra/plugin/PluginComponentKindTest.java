package io.github.actforever.kuudra.plugin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginComponentKindTest {
    @Test
    void exposesManifestKindsSeparatelyFromComponentReferencePrefixes() {
        assertEquals(Map.of(
                "event-source", "EventSource",
                "event-interpreter", "EventInterpreter",
                "event-adapter", "EventAdapter",
                "ingress", "Ingress",
                "event-handler", "EventHandler",
                "egress", "Egress"),
                java.util.Arrays.stream(PluginComponentKind.values()).collect(java.util.stream.Collectors.toMap(
                        PluginComponentKind::prefix, PluginComponentKind::manifestKind)));
    }
}
