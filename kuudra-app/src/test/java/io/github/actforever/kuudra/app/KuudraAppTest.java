package io.github.actforever.kuudra.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.actforever.kuudra.api.EventEmitter;
import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.api.EventDomain;
import io.github.actforever.kuudra.api.KuudraException;
import io.github.actforever.kuudra.config.KuudraConfigResource;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.KuudraFlow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuudraAppTest {
    @TempDir Path directory;

    @Test
    void exposesAppLifecycleWithoutLeakingRuntimeTypes() {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            assertEquals("RUNNING", app.health().status());
            assertEquals(0, app.flows().size());
            assertEquals("ACTIVE", app.plugin("kuudra-official", "default").orElseThrow().status());
            assertTrue(app.pluginComponent("ingress/kuudra-official/default").isPresent());
            assertTrue(app.componentResources().isEmpty(), "Loading the built-in plugin must not create resources");
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
            app.registerFlow(new KuudraFlow("flow", Map.of("sink", new FlowNode.AdapterNode("sink", (event, context) -> java.util.List.of(event), EventDomain.RAW)), Map.of()));
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
        assertFalse(Files.exists(home.resolve("flows")));
        assertTrue(Files.isDirectory(home.resolve("manifests")));
        assertTrue(Files.isDirectory(home.resolve("logs")));
        assertTrue(Files.isDirectory(home.resolve("state")));
        assertTrue(Files.readString(home.resolve("logs/latest.log")).contains("app.stopped"));
        Path archive;
        try (var files = Files.list(home.resolve("logs"))) {
            archive = files.filter(path -> path.getFileName().toString().endsWith(".log.gz")).findFirst().orElseThrow();
        }
        try (var input = new java.util.zip.GZIPInputStream(Files.newInputStream(archive))) {
            String log = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(log.contains("app.running"));
            assertTrue(log.contains("plugin.scan.completed"));
            assertTrue(log.contains("app.stopped"));
        }
    }

    @Test
    void initializesHomeWithPackagedConfigurationAndRequiredDirectories() throws Exception {
        Path home = directory.resolve(".kuudra");

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals("RUNNING", app.health().status());
        }

        assertTrue(Files.readString(home.resolve("config.yaml")).contains("home-directory: .kuudra"));
        assertTrue(Files.readString(home.resolve("config.yaml")).contains("max-event-hops: 256"));
        assertTrue(Files.readString(home.resolve("config.yaml")).contains("session-coordinator:"));
        assertTrue(Files.isDirectory(home.resolve("plugins")));
        assertFalse(Files.exists(home.resolve("flows")));
        assertTrue(Files.isDirectory(home.resolve("manifests")));
        assertTrue(Files.isDirectory(home.resolve("state")));
        assertTrue(Files.isDirectory(home.resolve("logs")));
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
    void appliesLoggingConfigurationFromHomeYaml() throws Exception {
        Path home = Files.createDirectories(directory.resolve(".kuudra"));
        Files.writeString(home.resolve("config.yaml"), """
                logging:
                  level: error
                  console-enabled: false
                  file-enabled: false
                """);

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals("RUNNING", app.health().status());
        }

        assertTrue(Files.isDirectory(home.resolve("logs")));
        assertEquals(false, Files.exists(home.resolve("logs/latest.log")));
    }

    @Test
    void rejectsEveryInvalidJarInTheFixedPluginDirectory() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve(".kuudra/plugins"));
        Files.writeString(plugins.resolve("not-a-kuudra-plugin.jar"), "invalid jar");

        assertThrows(KuudraException.class, () -> KuudraApp.createFromDefaultLocations(directory));
    }

    @Test
    void exposesEveryManifestComponentResourceTypeNotOnlyEventSources() throws Exception {
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(manifests.resolve("ingress.yaml"), component("Ingress", "ingress", "kuudra-official/default"));
        Files.writeString(manifests.resolve("egress.yaml"), component("Egress", "egress", "kuudra-official/default"));
        Files.writeString(manifests.resolve("flow.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata: {namespace: test, name: pipeline}
                spec:
                  desiredState: active
                  imports:
                    ingress: {kind: Ingress, namespace: test, name: ingress}
                    egress: {kind: Egress, namespace: test, name: egress}
                  edges:
                    - {from: ingress, to: egress}
                """);

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals(2, app.componentResources().size());
            KuudraApp.ComponentResource ingress = app.componentResource("ingress", "test", "ingress").orElseThrow();
            assertEquals("MATERIALIZED", ingress.status());
            assertEquals(java.util.List.of("test/pipeline"), ingress.importedBy());
            assertEquals(1, app.componentResources("egress").size());
            assertTrue(app.componentResources("event-handler").isEmpty());
            assertEquals("Ingress", app.resource("Ingress", "test", "ingress").orElseThrow().kind());
            assertEquals(2, app.resourcesInNamespace("test").size());
            assertEquals(1, app.flows("test").size());
        }
    }

    @Test
    void leavesInactivePassiveResourcesUnmaterialized() throws Exception {
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(manifests.resolve("ingress.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Ingress
                metadata: {namespace: test, name: dormant}
                spec:
                  component: kuudra-official/default
                  desiredState: inactive
                """);

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            KuudraApp.ComponentResource resource = app.resource("Ingress", "test", "dormant").orElseThrow();
            assertEquals("inactive", resource.desiredState());
            assertEquals("ABSENT", resource.status());
        }
    }

    private static String component(String kind, String name, String implementation) {
        return """
                apiVersion: kuudra.io/v1alpha1
                kind: %s
                metadata: {namespace: test, name: %s}
                spec:
                  component: %s
                  desiredState: active
                  options: {}
                """.formatted(kind, name, implementation);
    }
}
