package io.github.actforever.kuudra.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.actforever.kuudra.api.EventEmitter;
import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.api.EventDomain;
import io.github.actforever.kuudra.api.KuudraException;
import io.github.actforever.kuudra.api.PausableLifecycle;
import io.github.actforever.kuudra.config.KuudraConfigResource;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.KuudraFlow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuudraAppTest {
    @Test void pauseAndResumePreserveTheRunningKernel() {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            app.pause(); assertEquals("PAUSED", app.snapshot().status().name());
            assertTrue(app.checkpoint().isPresent());
            assertEquals(0, app.checkpoint().orElseThrow().runtime().queuedTasks());
            assertTrue(app.plugins().stream().anyMatch(plugin -> plugin.id().equals("default")));
            app.resume(); assertEquals("RUNNING", app.snapshot().status().name());
            assertTrue(app.checkpoint().isEmpty());
        }
    }

    @Test void pausedKernelCanBeStoppedAndRestartedAsAFreshKernel() {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            app.pause();
            assertEquals("PAUSED", app.snapshot().status().name());
            app.restart();
            assertEquals("RUNNING", app.snapshot().status().name());
            assertTrue(app.checkpoint().isEmpty());
        }
    }

    @Test void stopPreemptsAnInProgressPauseBarrier() throws Exception {
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (KuudraApp app = new KuudraApp(8, 1)) {
            app.registerFlow(new KuudraFlow("blocking", Map.of("node", new FlowNode.AdapterNode("node", (event, context) -> {
                executing.countDown();
                try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return java.util.List.of();
            }, EventDomain.RAW)), Map.of()));
            assertTrue(app.publish("blocking", "node", io.github.actforever.kuudra.api.KuudraEvent.of("blocking", Map.of())));
            assertTrue(executing.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> pausing = CompletableFuture.runAsync(app::pause);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (app.snapshot().status() != io.github.actforever.kuudra.api.AppStatus.PAUSING && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals("PAUSING", app.snapshot().status().name());
            app.stop();
            pausing.get(1, TimeUnit.SECONDS);
            assertEquals("STOPPED", app.snapshot().status().name());
        } finally {
            release.countDown();
        }
    }

    @Test void delegatesNonDestructivePauseAndResumeToCapableComponents() {
        AtomicInteger pauses = new AtomicInteger();
        AtomicInteger resumes = new AtomicInteger();
        class PausableSource implements EventSource, PausableLifecycle {
            @Override public void setEmitter(EventEmitter emitter) { }
            @Override public java.util.concurrent.CompletionStage<Void> pause() { pauses.incrementAndGet(); return CompletableFuture.completedFuture(null); }
            @Override public java.util.concurrent.CompletionStage<Void> resume() { resumes.incrementAndGet(); return CompletableFuture.completedFuture(null); }
        }
        try (KuudraApp app = new KuudraApp(8, 1)) {
            app.registerFlow(new KuudraFlow("flow", Map.of("sink", new FlowNode.AdapterNode("sink", (event, context) -> java.util.List.of(event), EventDomain.RAW)), Map.of()));
            app.declareEventSource("flow", "input", new PausableSource(), "sink");
            app.startEventSource("flow", "input");
            app.pause();
            assertEquals(1, pauses.get());
            app.resume();
            assertEquals(1, resumes.get());
        }
    }
    @TempDir Path directory;

    @Test
    void exposesAppLifecycleWithoutLeakingRuntimeTypes() {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            assertEquals("RUNNING", app.health().status());
            assertEquals(0, app.flows().size());
            assertEquals("ACTIVE", app.plugin("kuudra-official", "default").orElseThrow().status());
            assertTrue(app.pluginComponent("ingress/kuudra-official/default").isPresent());
            assertEquals(java.util.List.of("ACTIVE", "INACTIVE"), app.pluginComponent("ingress/kuudra-official/default")
                    .orElseThrow().documentation().supportedDesiredStates());
            assertEquals(java.util.List.of("RUNNING", "STOPPED"), app.pluginComponent("event-handler/kuudra-official/system-control")
                    .orElseThrow().documentation().supportedDesiredStates());
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
    void appOwnsTheBusUsedDirectlyByRuntimeModules() throws Exception {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            CopyOnWriteArrayList<String> types = new CopyOnWriteArrayList<>();
            try (AutoCloseable ignored = app.systemEvents().subscribe(event -> types.add(event.type()))) {
                app.registerFlow(new KuudraFlow("observed", Map.of(
                        "sink", new FlowNode.AdapterNode("sink", (event, context) -> java.util.List.of(event), EventDomain.RAW)), Map.of()));
                assertTrue(types.contains("flow.registered"));
            }
        }
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
                  imports:
                    ingress: {kind: Ingress, namespace: test, name: ingress}
                    egress: {kind: Egress, namespace: test, name: egress}
                  edges:
                    - {from: ingress, to: egress}
                """);

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals(2, app.componentResources().size());
            KuudraApp.ComponentResource ingress = app.componentResource("ingress", "test", "ingress").orElseThrow();
            assertEquals("ACTIVE", ingress.status());
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
            assertEquals("INACTIVE", resource.status());
        }
    }

    @Test
    void appReconcilesAndPersistsAComponentDesiredStateChange() throws Exception {
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(manifests.resolve("ingress.yaml"), component("Ingress", "switchable", "kuudra-official/default"));

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals("ACTIVE", app.resource("Ingress", "test", "switchable").orElseThrow().status());
            KuudraApp.ComponentResource inactive = app.setDesiredState("Ingress", "test", "switchable", "inactive");
            assertEquals("inactive", inactive.desiredState());
            assertEquals("INACTIVE", inactive.status());
            var state = app.resourceStates().stream().filter(item -> item.id().name().equals("switchable")).findFirst().orElseThrow();
            assertEquals(state.generation(), state.observedGeneration());
            assertEquals("READY", state.phase());
            assertEquals("ACTIVE", app.setDesiredState("Ingress", "test", "switchable", "active").status());
        }
    }

    @Test
    void startupManifestOverridesAConflictingPersistedDesiredState() throws Exception {
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(manifests.resolve("ingress.yaml"), component("Ingress", "authoritative", "kuudra-official/default"));
        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals("INACTIVE", app.setDesiredState("Ingress", "test", "authoritative", "inactive").status());
        }
        try (KuudraApp restarted = KuudraApp.createFromDefaultLocations(directory)) {
            KuudraApp.ComponentResource restored = restarted.resource("Ingress", "test", "authoritative").orElseThrow();
            assertEquals("active", restored.desiredState());
            assertEquals("ACTIVE", restored.status());
        }
    }

    @Test
    void restartReloadsTheAuthoritativeManifestDirectory() throws Exception {
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Path ingress = manifests.resolve("ingress.yaml");
        Files.writeString(ingress, component("Ingress", "before-restart", "kuudra-official/default"));

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertTrue(app.resource("Ingress", "test", "before-restart").isPresent());
            Files.writeString(ingress, component("Ingress", "after-restart", "kuudra-official/default"));
            app.restart();
            assertTrue(app.resource("Ingress", "test", "before-restart").isEmpty());
            assertTrue(app.resource("Ingress", "test", "after-restart").isPresent());
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
