package io.github.actforever.kuudra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KuudraConfigTest {
    @TempDir Path directory;

    @Test
    void loadsRuntimeAndFlowYamlFiles() throws Exception {
        Path flows = Files.createDirectory(directory.resolve("flows"));
        Files.writeString(directory.resolve("kuudra.yaml"), """
                runtime:
                  queueCapacity: 32
                  workerThreads: 2
                plugins:
                  directories: [plugins]
                flowsDirectory: flows
                globalContext:
                  profile: demo
                """);
        Files.writeString(flows.resolve("demo.yaml"), """
                id: demo
                components:
                  source:
                    type: event-source
                    component: event-source/demo/source
                    target: allocate
                    enabled: false
                  allocate:
                    type: session-allocator
                    options:
                      name: demo
                      policy: PARALLEL
                  actor:
                    type: actor
                    component: actor/demo/print
                routes:
                  - from: allocate
                    to: actor
                """);
        KuudraConfig.RuntimeConfig config = KuudraYamlLoader.load(directory.resolve("kuudra.yaml"));
        assertEquals(32, config.runtime().queueCapacity());
        assertEquals(directory.resolve(".kuudra/plugin-homes").toAbsolutePath().normalize(), config.pluginHomeDirectory());
        assertEquals("demo", config.flows().get("demo").id());
        assertEquals("actor", config.flows().get("demo").nodes().get("actor").type());
        assertEquals("source", config.flows().get("demo").sources().get(0).id());
        assertEquals(false, config.flows().get("demo").sources().get(0).enabled());
    }
    @Test
    void rejectsPluginComponentOmissionForNonCoreNodes() {
        assertThrows(IllegalArgumentException.class, () -> new KuudraConfig.NodeConfig("actor", "actor", null, Map.of()));
    }
}
