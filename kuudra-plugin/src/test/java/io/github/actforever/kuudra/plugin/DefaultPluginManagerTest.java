package io.github.actforever.kuudra.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPluginManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void startsDependenciesFirstAndDestroysThemLast() throws Exception {
        List<String> calls = new ArrayList<>();
        RecordingPlugin base = new RecordingPlugin("base", List.of(), calls);
        RecordingPlugin feature = new RecordingPlugin("feature", List.of("base"), calls);
        DefaultPluginManager manager = new DefaultPluginManager(temporaryDirectory.resolve("plugins"));
        manager.register(feature);
        manager.register(base);

        manager.startAll().toCompletableFuture().join();

        assertEquals(List.of("base.initialize", "base.start", "feature.initialize", "feature.start"), calls);
        assertEquals(PluginState.ACTIVE, manager.state("base"));
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("plugins/base")));
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("plugins/feature")));

        manager.stopAll().toCompletableFuture().join();

        assertEquals(List.of(
                "base.initialize", "base.start", "feature.initialize", "feature.start",
                "feature.stop", "feature.destroy", "base.stop", "base.destroy"), calls);
        assertEquals(PluginState.STOPPED, manager.state("feature"));
    }

    @Test
    void rejectsMissingDependenciesAndMarksLifecycleFailures() {
        DefaultPluginManager missingDependency = new DefaultPluginManager(temporaryDirectory.resolve("missing"));
        missingDependency.register(new RecordingPlugin("feature", List.of("absent"), new ArrayList<>()));
        assertThrows(IllegalStateException.class, () -> missingDependency.startAll());

        DefaultPluginManager brokenManager = new DefaultPluginManager(temporaryDirectory.resolve("broken"));
        brokenManager.register(new RecordingPlugin("broken", List.of(), new ArrayList<>()) {
            @Override
            public CompletionStage<Void> start() {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        });
        assertThrows(Exception.class, () -> brokenManager.startAll().toCompletableFuture().join());
        assertEquals(PluginState.FAILED, brokenManager.state("broken"));
    }

    @Test
    void closesPluginResourcesAfterDestroyInReverseRegistrationOrder() {
        List<String> calls = new ArrayList<>();
        DefaultPluginManager manager = new DefaultPluginManager(temporaryDirectory.resolve("resources"));
        manager.register(new RecordingPlugin("resource-owner", List.of(), calls) {
            @Override
            public CompletionStage<Void> initialize(PluginContext context) {
                super.initialize(context);
                context.resources().register("first", () -> calls.add("resource.first.close"));
                context.resources().register("second", () -> calls.add("resource.second.close"));
                return CompletableFuture.completedFuture(null);
            }
        });

        manager.startAll().toCompletableFuture().join();
        manager.stopAll().toCompletableFuture().join();

        assertEquals(List.of(
                "resource-owner.initialize", "resource-owner.start", "resource-owner.stop", "resource-owner.destroy",
                "resource.second.close", "resource.first.close"), calls);
    }

    @Test
    void metadataDependenciesAndAnnotatedComponentsBecomeConfigurationResources() throws Exception {
        PluginMetadata metadata = PluginMetadataToml.read(new java.io.ByteArrayInputStream("""
                id = "annotated"
                namespace = "test-plugin"
                version = "1.0.0"
                entrypoint = "example.Plugin"
                dependencies = ["base"]
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(List.of("base"), metadata.dependencies());
        assertEquals("test-plugin", metadata.namespace());
        DefaultPluginManager manager = new DefaultPluginManager(temporaryDirectory.resolve("annotated"));
        List<String> calls = new ArrayList<>();
        manager.register(new RecordingPlugin("base", List.of(), calls));
        manager.register(new PluginArchiveLoader.LoadedPlugin(metadata, new RecordingPlugin("annotated", List.of(), calls),
                List.of(new PluginComponentDefinition("annotated", "test-plugin", PluginComponentKind.EVENT_SOURCE, "test-source", TestSource.class))));
        manager.startAll().toCompletableFuture().join();
        assertEquals(List.of("base.initialize", "base.start", "annotated.initialize", "annotated.start"), calls);
        assertEquals("annotated", manager.components().find("event-source/test-plugin/test-source").orElseThrow().pluginId());
        assertTrue(manager.components().create("event-source/test-plugin/test-source", io.github.actforever.kuudra.api.EventSource.class) instanceof TestSource);
    }

    private static class RecordingPlugin implements KuudraPlugin {
        private final String id;
        private final List<String> requires;
        private final List<String> calls;

        private RecordingPlugin(String id, List<String> requires, List<String> calls) {
            this.id = id;
            this.requires = requires;
            this.calls = calls;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(id, requires);
        }

        @Override
        public CompletionStage<Void> initialize(PluginContext context) {
            calls.add(id + ".initialize");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> start() {
            calls.add(id + ".start");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> stop() {
            calls.add(id + ".stop");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> destroy() {
            calls.add(id + ".destroy");
            return CompletableFuture.completedFuture(null);
        }
    }

    @io.github.actforever.kuudra.plugin.annotation.EventSource("test-source")
    public static final class TestSource implements io.github.actforever.kuudra.api.EventSource {
        @Override public void setEmitter(io.github.actforever.kuudra.api.EventEmitter emitter) { }
        @Override public CompletionStage<Void> start() { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
    }
}
