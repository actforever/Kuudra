package io.github.actforever.kuudra.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.actforever.kuudra.api.event.EventEmitter;
import io.github.actforever.kuudra.api.component.EventSource;
import io.github.actforever.kuudra.api.event.EventDomain;
import io.github.actforever.kuudra.api.KuudraException;
import io.github.actforever.kuudra.api.lifecycle.PausableLifecycle;
import io.github.actforever.kuudra.config.KuudraConfigResource;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.KuudraFlow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuudraAppTest {
    @Test void providesEnglishMessagesAndAllowsExternalI18nOverrides() {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            assertTrue(app.systemEventMessageResolver().resolve("runtime.shutdown.started",
                    Map.of("activeSessions", 2, "queuedTasks", 3)).orElseThrow().startsWith("Runtime shutdown started"));
            app.setSystemEventMessageResolver((key, arguments) -> key.equals("runtime.shutdown.started")
                    ? Optional.of("CUSTOM " + arguments.get("activeSessions")) : Optional.empty());
            assertEquals("CUSTOM 2", app.systemEventMessageResolver().resolve("runtime.shutdown.started",
                    Map.of("activeSessions", 2, "queuedTasks", 3)).orElseThrow());
            assertTrue(app.systemEventMessageResolver().resolve("app.stopping",
                    Map.of("status", "STOPPING")).orElseThrow().startsWith("Kuudra App is stopping"));
        }
    }

    @Test void pauseAndResumePreserveTheRunningKernel() {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            app.pause(); assertEquals("PAUSED", app.snapshot().status().name());
            assertTrue(app.checkpoint().isPresent());
            assertEquals(0, app.checkpoint().orElseThrow().runtime().queuedTasks());
            assertTrue(app.plugins().isEmpty(), "The kernel must not inject a default plugin");
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
            assertTrue(app.publish("blocking", "node", io.github.actforever.kuudra.api.event.KuudraEvent.of("blocking", Map.of())));
            assertTrue(executing.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> pausing = CompletableFuture.runAsync(app::pause);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (app.snapshot().status() != io.github.actforever.kuudra.api.app.AppStatus.PAUSING && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals("PAUSING", app.snapshot().status().name());
            app.stop();
            pausing.get(1, TimeUnit.SECONDS);
            assertEquals("STOPPED", app.snapshot().status().name());
        } finally {
            release.countDown();
        }
    }

    @Test void kernelPauseDoesNotMutateComponentLifecycleState() {
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
            assertEquals(0, pauses.get());
            app.resume();
            assertEquals(0, resumes.get());
        }
    }
    @TempDir Path directory;

    @Test
    void exposesAppLifecycleWithoutLeakingRuntimeTypes() {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            assertEquals("RUNNING", app.health().status());
            assertEquals(0, app.flows().size());
            assertTrue(app.plugins().isEmpty());
            assertTrue(app.componentResources().isEmpty());
            KuudraApp.ResourceDocumentation flowDocumentation = app.resourceDocumentation("kuudra-official", "Flow").orElseThrow();
            assertEquals("Flow", flowDocumentation.kind());
            assertTrue(flowDocumentation.fields().stream().anyMatch(field -> field.path().equals("spec.imports")));
            assertEquals("dev", ((Map<?, ?>) flowDocumentation.examples().get(0).get("metadata")).get("namespace"));
            app.stop();
            assertEquals("STOPPED", app.snapshot().status().name());
            app.start();
            assertEquals("RUNNING", app.snapshot().status().name());
        }
    }

    @Test
    void exposesManifestReadyKindsForComponentTemplates() throws Exception {
        installDefaultTestPlugin();
        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            Map<String, String> kinds = app.components().stream().collect(java.util.stream.Collectors.toMap(
                    KuudraApp.Component::reference, KuudraApp.Component::kind));
            assertEquals("EventSource", kinds.get("event-source/kuudra-official/standalone-source"));
            assertEquals("Ingress", kinds.get("ingress/kuudra-official/default"));
            assertEquals("Egress", kinds.get("egress/kuudra-official/default"));
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
        assertTrue(Files.readString(home.resolve("logs/latest.log")).contains("Kuudra App has stopped"));
        Path archive;
        try (var files = Files.list(home.resolve("logs"))) {
            archive = files.filter(path -> path.getFileName().toString().endsWith(".log.gz")).findFirst().orElseThrow();
        }
        try (var input = new java.util.zip.GZIPInputStream(Files.newInputStream(archive))) {
            String log = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(log.contains("Kuudra App is running"));
            assertFalse(log.contains("Plugin archive scan completed"));
            assertFalse(log.contains("Stopping Runtime"));
            assertFalse(log.contains("Runtime shutdown started"));
            assertFalse(log.contains("Stopping plugins"));
            assertFalse(log.contains("Closing plugin archives"));
            assertFalse(log.contains("Flushing and archiving the current run log"));
            assertTrue(log.contains("Kuudra App has stopped"));
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
        assertTrue(Files.readString(home.resolve("config.yaml")).contains("reconciliation:"));
        assertTrue(Files.readString(home.resolve("config.yaml")).contains("# Kuudra 家目录"));
        assertTrue(Files.readString(home.resolve("config.yaml")).contains("session-coordinator:"));
        assertTrue(Files.isDirectory(home.resolve("plugins")));
        assertFalse(Files.exists(home.resolve("flows")));
        assertTrue(Files.isDirectory(home.resolve("manifests")));
        assertTrue(Files.isDirectory(home.resolve("state")));
        assertTrue(Files.isDirectory(home.resolve("logs")));
        String latestLog = Files.readString(home.resolve("logs/latest.log"));
        assertTrue(latestLog.contains("Created missing Kuudra home directory"));
        assertTrue(latestLog.contains("Restored missing Kuudra home configuration from packaged defaults"));
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
    void restartRecreatesHomeEntriesDeletedDuringThePreviousRun() throws Exception {
        Path home = directory.resolve(".kuudra");
        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            Files.delete(home.resolve("config.yaml"));
            Files.delete(home.resolve("locale"));
            app.restart();
            assertTrue(Files.isRegularFile(home.resolve("config.yaml")));
            assertTrue(Files.isDirectory(home.resolve("locale")));
        }
        String latestLog = Files.readString(home.resolve("logs/latest.log"));
        assertTrue(latestLog.contains("role=locale"));
        assertTrue(latestLog.contains("Restored missing Kuudra home configuration from packaged defaults"));
    }

    @Test
    void standaloneManifestEventSourceReconcilesToRunningWithoutAFlow() throws Exception {
        installDefaultTestPlugin();
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(manifests.resolve("standalone-source.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: EventSource
                metadata: {namespace: test, name: standalone}
                spec:
                  component: kuudra-official/standalone-source
                  desiredState: running
                  options: {}
                """);
        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            KuudraApp.ComponentResource source = app.resource("EventSource", "test", "standalone").orElseThrow();
            assertEquals("running", source.desiredState());
            assertEquals("RUNNING", source.status());
            assertTrue(source.importedBy().isEmpty());
        }
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
        installDefaultTestPlugin();
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
            app.pause();
            KuudraApp.ComponentResource suspended = app.componentResource("ingress", "test", "ingress").orElseThrow();
            assertEquals("ACTIVE", suspended.status());
            assertEquals("SUSPENDED", suspended.effectiveStatus());
            assertFalse(suspended.available());
            assertEquals(java.util.List.of("KERNEL"), suspended.suspensionReasons());
            app.resume();
            assertEquals("ACTIVE", app.componentResource("ingress", "test", "ingress").orElseThrow().effectiveStatus());
        }
    }

    @Test
    void reportsInactivePassiveResourcesWithoutEnablingThem() throws Exception {
        installDefaultTestPlugin();
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
    void includesOnlySelectedNamespacesWhileKeepingAllDeclarationsQueryable() throws Exception {
        installDefaultTestPlugin();
        Path home = Files.createDirectories(directory.resolve(".kuudra"));
        Path manifests = Files.createDirectories(home.resolve("manifests"));
        Files.writeString(home.resolve("config.yaml"), """
                home-directory: .kuudra
                resource-selection:
                  namespace-mode: INCLUDE
                  namespaces: [alpha]
                """);
        Files.writeString(manifests.resolve("namespaces.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Ingress
                metadata: {namespace: alpha, name: input}
                spec: {component: kuudra-official/default, desiredState: active}
                ---
                apiVersion: kuudra.io/v1alpha1
                kind: Egress
                metadata: {namespace: alpha, name: output}
                spec: {component: kuudra-official/default, desiredState: active}
                ---
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata: {namespace: alpha, name: route}
                spec:
                  imports:
                    input: {kind: Ingress, name: input}
                    output: {kind: Egress, name: output}
                  edges: [{from: input, to: output}]
                ---
                apiVersion: kuudra.io/v1alpha1
                kind: Ingress
                metadata: {namespace: beta, name: input}
                spec: {component: kuudra-official/default, desiredState: active}
                ---
                apiVersion: kuudra.io/v1alpha1
                kind: Egress
                metadata: {namespace: beta, name: output}
                spec: {component: kuudra-official/default, desiredState: active}
                ---
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata: {namespace: beta, name: route}
                spec:
                  imports:
                    input: {kind: Ingress, name: input}
                    output: {kind: Egress, name: output}
                  edges: [{from: input, to: output}]
                """);

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals(4, app.componentResources().size());
            assertTrue(app.resource("Ingress", "alpha", "input").orElseThrow().selected());
            assertEquals("ACTIVE", app.resource("Ingress", "alpha", "input").orElseThrow().status());
            assertEquals(false, app.resource("Ingress", "beta", "input").orElseThrow().selected());
            assertEquals("EXCLUDED", app.resource("Ingress", "beta", "input").orElseThrow().status());
            assertEquals(2, app.flows().size());
            assertTrue(app.flow("alpha", "route").orElseThrow().selected());
            assertEquals(false, app.flow("beta", "route").orElseThrow().selected());
            assertEquals("EXCLUDED", app.resourceStates().stream()
                    .filter(state -> state.id().namespace().equals("beta")).findFirst().orElseThrow().phase());
            assertEquals("INACTIVE", app.setDesiredState("Ingress", "alpha", "input", "inactive").status());
            assertEquals(4, app.componentResources().size());
            assertTrue(app.resource("Ingress", "beta", "input").isPresent());
            assertThrows(io.github.actforever.kuudra.api.KuudraException.class,
                    () -> app.setDesiredState("Ingress", "beta", "input", "inactive"));
        }
    }

    @Test
    void inactivePassiveResourceMayRemainImportedButItsRuntimeGateIsClosed() throws Exception {
        installDefaultTestPlugin();
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(manifests.resolve("resources.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Ingress
                metadata: {namespace: test, name: dormant}
                spec: {component: kuudra-official/default, desiredState: inactive}
                ---
                apiVersion: kuudra.io/v1alpha1
                kind: Egress
                metadata: {namespace: test, name: output}
                spec: {component: kuudra-official/default, desiredState: active}
                ---
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata: {namespace: test, name: dormant-route}
                spec:
                  imports:
                    ingress: {kind: Ingress, name: dormant}
                    output: {kind: Egress, name: output}
                  edges: [{from: ingress, to: output}]
                """);
        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            assertEquals("INACTIVE", app.resource("Ingress", "test", "dormant").orElseThrow().status());
            assertEquals(java.util.List.of("test/dormant-route"), app.resource("Ingress", "test", "dormant")
                    .orElseThrow().importedBy());
        }
    }

    @Test
    void appReconcilesAndPersistsAComponentDesiredStateChange() throws Exception {
        installDefaultTestPlugin();
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(manifests.resolve("ingress.yaml"), component("Ingress", "switchable", "kuudra-official/default"));

        try (KuudraApp app = KuudraApp.createFromDefaultLocations(directory)) {
            CopyOnWriteArrayList<io.github.actforever.kuudra.api.system.SystemEvent> observedEvents = new CopyOnWriteArrayList<>();
            try (AutoCloseable ignored = app.systemEvents().subscribe(observedEvents::add)) {
            assertEquals("ACTIVE", app.resource("Ingress", "test", "switchable").orElseThrow().status());
            KuudraApp.ComponentResource inactive = app.setDesiredState("Ingress", "test", "switchable", "inactive");
            assertEquals("inactive", inactive.desiredState());
            assertEquals("INACTIVE", inactive.status());
            var state = app.resourceStates().stream().filter(item -> item.id().name().equals("switchable")).findFirst().orElseThrow();
            assertEquals(state.generation(), state.observedGeneration());
            assertEquals("READY", state.phase());
            assertEquals("ACTIVE", app.setDesiredState("Ingress", "test", "switchable", "active").status());
            assertTrue(observedEvents.stream().anyMatch(event -> event.type().equals("component.state.changed")
                    && event.level() == io.github.actforever.kuudra.api.system.SystemEventLevel.DEBUG
                    && event.data().get("from").equals("ACTIVE") && event.data().get("to").equals("INACTIVE")));
            assertTrue(observedEvents.stream().anyMatch(event -> event.type().equals("resource.state.changed")
                    && event.level() == io.github.actforever.kuudra.api.system.SystemEventLevel.AUTO
                    && event.data().get("from").equals("ACTIVE") && event.data().get("to").equals("INACTIVE")));
            }
        }
    }

    @Test void periodicReconciliationPublishesTraceForEveryCycle() throws Exception {
        KuudraConfigResource configuration = new KuudraConfigResource(Map.of(
                "home-directory", ".kuudra",
                "reconciliation", Map.of("enabled", true, "interval-ms", 10),
                "logging", Map.of("level", "off", "console-enabled", false, "file-enabled", false)),
                directory, "trace reconciliation test");
        try (KuudraApp app = KuudraApp.createConfigured(configuration)) {
            CopyOnWriteArrayList<io.github.actforever.kuudra.api.system.SystemEvent> events = new CopyOnWriteArrayList<>();
            try (AutoCloseable ignored = app.systemEvents().subscribe(events::add)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (events.stream().noneMatch(event -> event.type().equals("reconciliation.cycle.completed"))
                        && System.nanoTime() < deadline) Thread.sleep(5);
                assertTrue(events.stream().anyMatch(event -> event.type().equals("reconciliation.cycle.started")
                        && event.level() == io.github.actforever.kuudra.api.system.SystemEventLevel.TRACE));
                assertTrue(events.stream().anyMatch(event -> event.type().equals("reconciliation.cycle.completed")
                        && event.level() == io.github.actforever.kuudra.api.system.SystemEventLevel.TRACE));
            }
        }
    }

    @Test void periodicReconciliationRetriesAFailedGenerationUntilItConverges() throws Exception {
        installDefaultTestPlugin();
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(manifests.resolve("flaky.yaml"), component(
                "EventSource", "flaky", "kuudra-official/flaky-source").replace("desiredState: active", "desiredState: stopped"));
        KuudraConfigResource configuration = new KuudraConfigResource(Map.of(
                "home-directory", ".kuudra",
                "reconciliation", Map.of("enabled", true, "interval-ms", 10),
                "logging", Map.of("level", "off", "console-enabled", false, "file-enabled", false)),
                directory, "retry reconciliation test");

        try (KuudraApp app = KuudraApp.createConfigured(configuration)) {
            CopyOnWriteArrayList<io.github.actforever.kuudra.api.system.SystemEvent> events = new CopyOnWriteArrayList<>();
            try (AutoCloseable ignored = app.systemEvents().subscribe(events::add)) {
                TestDefaultPlugin.FlakySource.failNextStart();
                assertThrows(RuntimeException.class,
                        () -> app.setDesiredState("EventSource", "test", "flaky", "running"));
                assertEquals("FAILED", app.resourceStates().stream()
                        .filter(state -> state.id().name().equals("flaky")).findFirst().orElseThrow().phase());

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!"RUNNING".equals(app.resource("EventSource", "test", "flaky").orElseThrow().status())
                        && System.nanoTime() < deadline) Thread.sleep(5);

                assertEquals("RUNNING", app.resource("EventSource", "test", "flaky").orElseThrow().status());
                var state = app.resourceStates().stream()
                        .filter(item -> item.id().name().equals("flaky")).findFirst().orElseThrow();
                assertEquals("READY", state.phase());
                assertEquals(state.generation(), state.observedGeneration());
                assertTrue(events.stream().anyMatch(event -> event.type().equals("resource.reconcile.retry")));
                assertTrue(events.stream().anyMatch(event -> event.type().equals("resource.state.changed")
                        && event.data().get("to").equals("RUNNING")));
            }
        }
    }

    @Test
    void startupManifestOverridesAConflictingPersistedDesiredState() throws Exception {
        installDefaultTestPlugin();
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
    void controlPlaneResourceQueriesRemainAvailableAfterRuntimeStops() throws Exception {
        installDefaultTestPlugin();
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(manifests.resolve("ingress.yaml"), component("Ingress", "queryable", "kuudra-official/default"));
        KuudraApp app = KuudraApp.createFromDefaultLocations(directory);
        try {
            app.stop();
            KuudraApp.ComponentResource resource = app.resource("Ingress", "test", "queryable").orElseThrow();
            assertEquals("active", resource.desiredState());
            assertEquals("NOT_RUNNING", resource.status());
            assertEquals(1, app.resourceStates().size());
        } finally {
            app.close();
        }
    }

    @Test
    void restartReloadsTheAuthoritativeManifestDirectory() throws Exception {
        installDefaultTestPlugin();
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

    private void installDefaultTestPlugin() throws Exception {
        Path jar = Files.createDirectories(directory.resolve(".kuudra/plugins")).resolve("default-test-plugin.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            write(output, "META-INF/kuudra-plugin/metadata.toml", """
                    id = "default"
                    namespace = "kuudra-official"
                    version = "0.1.0"
                    entrypoint = "io.github.actforever.kuudra.app.TestDefaultPlugin"
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (Class<?> type : java.util.List.of(TestDefaultPlugin.class, TestDefaultPlugin.TestIngress.class,
                    TestDefaultPlugin.TestEgress.class, TestDefaultPlugin.TestSource.class,
                    TestDefaultPlugin.FlakySource.class)) {
                String resource = type.getName().replace('.', '/') + ".class";
                try (var input = type.getClassLoader().getResourceAsStream(resource)) {
                    write(output, resource, java.util.Objects.requireNonNull(input).readAllBytes());
                }
            }
        }
    }

    private static void write(JarOutputStream output, String name, byte[] content) throws Exception {
        output.putNextEntry(new JarEntry(name)); output.write(content); output.closeEntry();
    }
}
