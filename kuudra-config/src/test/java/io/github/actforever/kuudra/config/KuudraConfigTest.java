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
    void loadsFrameworkNeutralConfigurationResource() throws Exception {
        KuudraConfig.RuntimeConfig config = KuudraYamlLoader.load(new KuudraConfigResource(Map.of(
                "runtime", Map.of("queue-capacity", 48, "worker-threads", 3),
                "home-directory", "custom-home",
                "global-context", Map.of("profile", "host")), directory, "host configuration"));

        assertEquals(48, config.runtime().queueCapacity());
        assertEquals(3, config.runtime().workerThreads());
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
                kind: Component
                metadata:
                  namespace: input
                  name: keyboard
                  labels:
                    device: keyboard
                spec:
                  type: event-source
                  component: native-input/keyboard
                  desiredState: stopped
                  options:
                    mouseEnabled: false
                """);
        Files.writeString(manifests.resolve("flow.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata:
                  namespace: macros
                  name: demo
                spec:
                  imports:
                    input:
                      kind: Component
                      namespace: input
                      name: keyboard
                    handler:
                      kind: Component
                      namespace: actions
                      name: printer
                  edges:
                    - from: input
                      to: handler
                """);

        KuudraConfig.RuntimeConfig config = KuudraYamlLoader.load(directory.resolve("config.yaml"));
        KuudraManifest.Component source = config.manifests().components().get(
                new KuudraManifest.ResourceId("Component", "input", "keyboard"));
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
                kind: Component
                metadata: {namespace: default, name: duplicate}
                spec: {type: event-handler, component: demo/handler}
                """;
        Files.writeString(manifests.resolve("one.yaml"), component);
        Files.writeString(directory.resolve(".kuudra/manifests/b/two.yaml"), component);

        assertThrows(java.io.IOException.class, () -> KuudraYamlLoader.load(directory.resolve("config.yaml")));
    }
}
