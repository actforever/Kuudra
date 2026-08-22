package io.github.actforever.kuudra.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.actforever.kuudra.api.EventEmitter;
import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.config.KuudraConfigResource;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.KuudraFlow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuudraAppTest {
    @TempDir Path directory;

    @Test
    void exposesAppLifecycleWithoutLeakingRuntimeTypes() {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            assertEquals("RUNNING", app.health().status());
            assertEquals(0, app.flows().size());
            app.stop();
            assertEquals("STOPPED", app.snapshot().status().name());
            app.start();
            assertEquals("RUNNING", app.snapshot().status().name());
        }
    }

    @Test
    void eventSourceIsAnIndependentlyControllableFlowResource() {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        EventSource source = new EventSource() {
            @Override public void setEmitter(EventEmitter emitter) { }
            @Override public java.util.concurrent.CompletionStage<Void> start() { starts.incrementAndGet(); return CompletableFuture.completedFuture(null); }
            @Override public java.util.concurrent.CompletionStage<Void> stop() { stops.incrementAndGet(); return CompletableFuture.completedFuture(null); }
        };
        try (KuudraApp app = new KuudraApp(8, 1)) {
            app.registerFlow(new KuudraFlow("flow", Map.of("sink", new FlowNode.AdapterNode("sink", (event, context) -> java.util.List.of(event))), Map.of()));
            assertEquals("STOPPED", app.declareEventSource("flow", "input", source, "sink").status());
            assertEquals("RUNNING", app.startEventSource("flow", "input").status());
            assertEquals("STOPPED", app.stopEventSource("flow", "input").status());
            assertEquals(1, starts.get());
            assertEquals(1, stops.get());
        }
    }

    @Test
    void loadsHomeConfigurationOverPackagedDefaults() throws Exception {
        Path home = Files.createDirectories(directory.resolve(".kuudra"));
        String userConfiguration = """
                global-context:
                  source: home
                """;
        Files.writeString(home.resolve("config.yaml"), userConfiguration);

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals("home", app.globalContext().get("source"));
        }
        assertEquals(userConfiguration, Files.readString(home.resolve("config.yaml")));
        assertTrue(Files.isDirectory(home.resolve("plugins")));
        assertTrue(Files.isDirectory(home.resolve("flows")));
    }

    @Test
    void initializesHomeWithPackagedConfigurationAndRequiredDirectories() throws Exception {
        Path home = directory.resolve(".kuudra");

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals("RUNNING", app.health().status());
        }

        assertTrue(Files.readString(home.resolve("config.yaml")).contains("home-directory: .kuudra"));
        assertTrue(Files.isDirectory(home.resolve("plugins")));
        assertTrue(Files.isDirectory(home.resolve("flows")));
    }

    @Test
    void deletingInvalidHomeConfigurationRestoresPackagedDefaultsOnRestart() throws Exception {
        Path home = Files.createDirectories(directory.resolve(".kuudra"));
        Path configuration = home.resolve("config.yaml");
        Files.writeString(configuration, "- invalid-root");
        assertThrows(java.io.IOException.class, () -> KuudraApp.createFromDefaultLocations(directory));

        Files.delete(configuration);
        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals("RUNNING", app.health().status());
        }
        assertTrue(Files.readString(configuration).contains("global-context: {}"));
    }

    @Test
    void programmaticConfigurationOverridesHomeConfiguration() throws Exception {
        Path home = Files.createDirectories(directory.resolve("custom-home"));
        Files.writeString(home.resolve("config.yaml"), """
                global-context:
                  source: home
                  retained: true
                """);
        KuudraConfigResource explicit = new KuudraConfigResource(Map.of(
                "home-directory", "custom-home",
                "global-context", Map.of("source", "explicit")), directory, "test configuration");

        try (KuudraApp app = KuudraApp.createConfigured(explicit)) {
            assertEquals("explicit", app.globalContext().get("source"));
            assertEquals(true, app.globalContext().get("retained"));
        }
    }

    @Test
    void rejectsEveryInvalidJarInTheFixedPluginDirectory() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve(".kuudra/plugins"));
        Files.writeString(plugins.resolve("not-a-kuudra-plugin.jar"), "invalid jar");

        assertThrows(IllegalStateException.class, () -> KuudraApp.createFromDefaultLocations(directory));
    }
}
