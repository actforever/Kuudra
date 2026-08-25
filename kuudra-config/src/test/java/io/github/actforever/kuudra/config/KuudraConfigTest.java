package io.github.actforever.kuudra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuudraConfigTest {
    @TempDir Path directory;

    @Test
    void loadsFrameworkNeutralConfigurationResource() throws Exception {
        KuudraConfig.RuntimeConfig config = KuudraYamlLoader.load(new KuudraConfigResource(Map.of(
                "runtime", Map.of("queue-capacity", 48, "worker-threads", 3, "max-event-hops", 99,
                        "dispatcher-poll-interval-ms", 75, "shutdown-session-drain-timeout-ms", 900,
                        "session-coordinator", Map.of("default-policy", "serial", "default-group-scope", "ingress",
                                "max-parallel-sessions", 7, "queue-capacity", 11)),
                "reconciliation", Map.of("enabled", true, "interval-ms", 250),
                "state-store", Map.of("busy-timeout-ms", 1_500),
                "home-directory", "custom-home",
                "global-context", Map.of("profile", "host")), directory, "host configuration"));

        assertEquals(48, config.runtime().queueCapacity());
        assertEquals(3, config.runtime().workerThreads());
        assertEquals(99, config.runtime().maxEventHops());
        assertEquals(75, config.runtime().dispatcherPollIntervalMs());
        assertEquals(900, config.runtime().shutdownSessionDrainTimeoutMs());
        assertEquals(io.github.actforever.kuudra.api.SessionSchedulingPolicy.SERIAL, config.runtime().sessionCoordinator().defaultPolicy());
        assertEquals(io.github.actforever.kuudra.api.SessionGroupScope.INGRESS, config.runtime().sessionCoordinator().defaultGroupScope());
        assertEquals(7, config.runtime().sessionCoordinator().maxParallelSessions());
        assertEquals(11, config.runtime().sessionCoordinator().queueCapacity());
        assertTrue(config.reconciliation().enabled());
        assertEquals(250, config.reconciliation().intervalMs());
        assertEquals(1_500, config.stateStore().busyTimeoutMs());
        assertEquals("INFO", config.logging().level());
        assertEquals(true, config.logging().consoleEnabled());
        assertEquals(true, config.logging().fileEnabled());
        assertEquals(directory.resolve("custom-home").toAbsolutePath().normalize(), config.homeDirectory());
        assertEquals("host", config.globalContext().get("profile"));
    }

    @Test
    void rejectsRemovedPluginAndFlowDirectoryConfiguration() {
        assertThrows(java.io.IOException.class, () -> KuudraYamlLoader.load(new KuudraConfigResource(
                Map.of("plugins", Map.of()), directory, "legacy plugin configuration")));
        assertThrows(java.io.IOException.class, () -> KuudraYamlLoader.load(new KuudraConfigResource(
                Map.of("flows-directory", "flows"), directory, "legacy flow configuration")));
    }

    @Test
    void rejectsUnsupportedLoggingLevel() {
        assertThrows(java.io.IOException.class, () -> KuudraYamlLoader.load(new KuudraConfigResource(
                Map.of("logging", Map.of("level", "verbose")), directory, "invalid logging configuration")));
    }

    @Test
    void recursivelyLoadsKubernetesStyleComponentAndFlowManifests() throws Exception {
        Path home = Files.createDirectories(directory.resolve(".kuudra"));
        Path manifests = Files.createDirectories(home.resolve("manifests/nested"));
        Files.writeString(directory.resolve("config.yaml"), "home-directory: .kuudra\n");
        Files.writeString(manifests.resolve("source.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: EventSource
                metadata:
                  namespace: input
                  name: keyboard
                  labels:
                    device: keyboard
                spec:
                  component: native-input/keyboard
                  desiredState: stopped
                  options:
                    mouseEnabled: false
                """);
        Files.writeString(manifests.resolve("flow.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata:
                  namespace: input
                  name: demo
                spec:
                  imports:
                    input:
                      kind: EventSource
                      namespace: input
                      name: keyboard
                    handler:
                      kind: EventHandler
                      namespace: input
                      name: printer
                  edges:
                    - from: input
                      to: handler
                """);

        KuudraConfig.RuntimeConfig config = KuudraYamlLoader.load(directory.resolve("config.yaml"));
        KuudraManifest.Component source = config.manifests().components().get(
                new KuudraManifest.ResourceId("EventSource", "input", "keyboard"));
        assertEquals("stopped", source.desiredState());
        assertEquals(false, source.options().get("mouseEnabled"));
        assertEquals("keyboard", config.manifests().flows().values().iterator().next().imports().get("input").name());
    }

    @Test
    void rejectsDuplicateManifestIdentityAcrossDirectories() throws Exception {
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests/a"));
        Files.createDirectories(directory.resolve(".kuudra/manifests/b"));
        Files.writeString(directory.resolve("config.yaml"), "home-directory: .kuudra\n");
        String component = """
                apiVersion: kuudra.io/v1alpha1
                kind: EventHandler
                metadata: {namespace: default, name: duplicate}
                spec: {component: demo/handler}
                """;
        Files.writeString(manifests.resolve("one.yaml"), component);
        Files.writeString(directory.resolve(".kuudra/manifests/b/two.yaml"), component);

        assertThrows(java.io.IOException.class, () -> KuudraYamlLoader.load(directory.resolve("config.yaml")));
    }

    @Test
    void loadsMultipleResourcesFromOneYamlDocumentStream() throws Exception {
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(directory.resolve("config.yaml"), "home-directory: .kuudra\n");
        Files.writeString(manifests.resolve("pipeline.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Ingress
                metadata: {namespace: demo, name: ingress}
                spec: {component: kuudra-official/default}
                ---
                apiVersion: kuudra.io/v1alpha1
                kind: Egress
                metadata: {namespace: demo, name: egress}
                spec: {component: kuudra-official/default}
                ---
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata: {namespace: demo, name: pipeline}
                spec:
                  imports:
                    ingress: {kind: Ingress, name: ingress}
                    egress: {kind: Egress, name: egress}
                  edges: [{from: ingress, to: egress}]
                """);

        KuudraManifest.Resources resources = KuudraYamlLoader.load(directory.resolve("config.yaml")).manifests();

        assertEquals(2, resources.components().size());
        assertEquals(1, resources.flows().size());
        assertEquals("demo", resources.flows().values().iterator().next().imports().get("ingress").namespace());
    }

    @Test
    void rejectsLegacyComponentKindAndCrossNamespaceImports() throws Exception {
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(directory.resolve("config.yaml"), "home-directory: .kuudra\n");
        Files.writeString(manifests.resolve("handler.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: EventHandler
                metadata: {namespace: shared, name: handler}
                spec: {component: demo/handler}
                """);
        Files.writeString(manifests.resolve("flow.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata: {namespace: isolated, name: flow}
                spec:
                  imports:
                    handler: {kind: EventHandler, namespace: shared, name: handler}
                  edges: []
                """);
        assertThrows(java.io.IOException.class, () -> KuudraYamlLoader.load(directory.resolve("config.yaml")));

        Files.writeString(manifests.resolve("handler.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Component
                metadata: {namespace: isolated, name: legacy}
                spec: {type: event-handler, component: demo/handler}
                """);
        assertThrows(java.io.IOException.class, () -> KuudraYamlLoader.load(directory.resolve("config.yaml")));
    }

    @Test
    void reportsManifestIdentityFileLineAndExpectedShapeForMissingEdges() throws Exception {
        Path manifests = Files.createDirectories(directory.resolve(".kuudra/manifests"));
        Files.writeString(directory.resolve("config.yaml"), "home-directory: .kuudra\n");
        Path invalid = manifests.resolve("broken-flow.yaml");
        Files.writeString(invalid, """
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata: {namespace: demo, name: broken}
                spec:
                  imports:
                    ingress: {kind: Ingress, name: ingress}
                """);

        java.io.IOException error = assertThrows(java.io.IOException.class,
                () -> KuudraYamlLoader.load(directory.resolve("config.yaml")));
        assertTrue(error.getMessage().contains("broken-flow.yaml#document-1"));
        assertTrue(error.getMessage().contains("Flow demo/broken"));
        assertTrue(error.getMessage().contains("spec.edges"));
        assertTrue(error.getMessage().contains("near line "));
        assertTrue(error.getMessage().contains("{from: source, to: ingress}"));
    }
}
