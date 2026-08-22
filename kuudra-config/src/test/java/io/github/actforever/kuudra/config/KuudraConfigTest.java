package io.github.actforever.kuudra.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KuudraConfigTest {
    @Test
    void modelsAnEventGraphWithoutChoosingASerializationFormat() {
        KuudraConfig.FlowConfig flow = new KuudraConfig.FlowConfig("demo", Map.of(
                "normalize", new KuudraConfig.NodeConfig("normalize", "event-adapter", "event-adapter/input/normalize", Map.of()),
                "allocate", new KuudraConfig.NodeConfig("allocate", "session-allocator", null, Map.of("policy", "PARALLEL"))
        ), List.of(new KuudraConfig.EdgeConfig("normalize", "allocate")), List.of(new KuudraConfig.SourceBinding("event-source/input/keyboard", "normalize")));
        assertEquals("demo", flow.id()); assertEquals("session-allocator", flow.nodes().get("allocate").type());
    }
    @Test
    void rejectsPluginComponentOmissionForNonCoreNodes() {
        assertThrows(IllegalArgumentException.class, () -> new KuudraConfig.NodeConfig("actor", "actor", null, Map.of()));
    }
}
