package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.KuudraException;
import io.github.actforever.kuudra.api.event.EventDomain;
import io.github.actforever.kuudra.config.KuudraConfig;
import io.github.actforever.kuudra.config.KuudraManifest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdapterDomainInferenceTest {
    @Test void infersRawAcrossAnAdapterChain() {
        Fixture fixture = fixture(Map.of("source", "EventSource", "first", "EventAdapter", "second", "EventAdapter", "ingress", "Ingress"),
                List.of(edge("source", "first"), edge("first", "second"), edge("second", "ingress")));
        assertEquals(Map.of("first", EventDomain.RAW, "second", EventDomain.RAW),
                KuudraApp.inferAdapterDomains(fixture.flow, fixture.components));
    }

    @Test void infersSessionBetweenIngressAndHandler() {
        Fixture fixture = fixture(Map.of("ingress", "Ingress", "adapter", "EventAdapter", "handler", "EventHandler"),
                List.of(edge("ingress", "adapter"), edge("adapter", "handler")));
        assertEquals(EventDomain.SESSION, KuudraApp.inferAdapterDomains(fixture.flow, fixture.components).get("adapter"));
    }

    @Test void rejectsAmbiguousAndConflictingBindings() {
        Fixture ambiguous = fixture(Map.of("adapter", "EventAdapter"), List.of());
        assertThrows(KuudraException.class, () -> KuudraApp.inferAdapterDomains(ambiguous.flow, ambiguous.components));
        Fixture conflict = fixture(Map.of("ingress", "Ingress", "adapter", "EventAdapter", "interpreter", "EventInterpreter"),
                List.of(edge("ingress", "adapter"), edge("adapter", "interpreter")));
        assertThrows(KuudraException.class, () -> KuudraApp.inferAdapterDomains(conflict.flow, conflict.components));
    }

    private Fixture fixture(Map<String, String> kinds, List<KuudraConfig.EdgeConfig> edges) {
        Map<KuudraManifest.ResourceId, KuudraManifest.Component> components = new LinkedHashMap<>();
        Map<String, KuudraManifest.ResourceReference> imports = new LinkedHashMap<>();
        kinds.forEach((name, kind) -> {
            KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(kind, "test", name);
            components.put(id, new KuudraManifest.Component(id, new KuudraManifest.Metadata("test", name, Map.of(), Map.of()),
                    "test/fixture/" + name, kind.equals("EventAdapter") || kind.equals("Ingress") ? "active" : "running", Map.of()));
            imports.put(name, new KuudraManifest.ResourceReference(kind, "test", name));
        });
        KuudraManifest.ResourceId flowId = new KuudraManifest.ResourceId("Flow", "test", "flow");
        return new Fixture(new KuudraManifest.Flow(flowId, new KuudraManifest.Metadata("test", "flow", Map.of(), Map.of()), imports, edges), components);
    }

    private KuudraConfig.EdgeConfig edge(String from, String to) { return new KuudraConfig.EdgeConfig(from, to); }
    private record Fixture(KuudraManifest.Flow flow, Map<KuudraManifest.ResourceId, KuudraManifest.Component> components) {}
}
