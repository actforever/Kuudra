package io.github.actforever.kuudra.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;

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
    void publishesPluginLifecycleEventsWithoutDependingOnLogging() {
        List<String> eventTypes = new ArrayList<>();
        SystemEventBus bus = new SystemEventBus() {
            @Override public AutoCloseable subscribe(Consumer<SystemEvent> listener) { return () -> { }; }
            @Override public void publish(SystemEvent event) { eventTypes.add(event.type()); }
        };
        DefaultPluginManager manager = new DefaultPluginManager(temporaryDirectory.resolve("events"),
                PluginRuntimeServices.unavailable(), bus);
        manager.register(new RecordingPlugin("observed", List.of(), new ArrayList<>()));

        manager.startAll().toCompletableFuture().join();
        manager.stopAll().toCompletableFuture().join();

        assertTrue(eventTypes.containsAll(List.of("plugin.registered", "plugin.initializing", "plugin.initialized",
                "plugin.starting", "plugin.active", "plugin.stopping", "plugin.stopped")));
    }

    @Test
    void failedDependentCleansItselfAndPreviouslyStartedDependencies() {
        List<String> calls = new ArrayList<>();
        DefaultPluginManager manager = new DefaultPluginManager(temporaryDirectory.resolve("rollback"));
        manager.register(new RecordingPlugin("base", List.of(), calls));
        manager.register(new RecordingPlugin("child", List.of("base"), calls) {
            @Override public CompletionStage<Void> start() {
                calls.add("child.start");
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        });

        assertThrows(Exception.class, () -> manager.startAll().toCompletableFuture().join());
        manager.close();

        assertEquals(List.of(
                "base.initialize", "base.start", "child.initialize", "child.start", "child.destroy",
                "base.stop", "base.destroy"), calls);
        assertEquals(PluginState.FAILED, manager.state("child"));
        assertEquals(PluginState.STOPPED, manager.state("base"));
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

    @Test
    void componentLifecycleReceivesPluginHomeAndEndsBeforePluginShutdown() {
        List<String> calls = new ArrayList<>();
        ManagedTestSource.calls = calls;
        DefaultPluginManager manager = new DefaultPluginManager(temporaryDirectory.resolve("component-homes"));
        manager.register(new PluginArchiveLoader.LoadedPlugin(
                new PluginMetadata("component", "test-plugin", "1.0.0", "example.Plugin", List.of()),
                new RecordingPlugin("component", List.of(), calls),
                List.of(new PluginComponentDefinition("component", "test-plugin", PluginComponentKind.EVENT_SOURCE, "managed", ManagedTestSource.class))));
        manager.startAll().toCompletableFuture().join();
        manager.createComponent("event-source/test-plugin/managed", io.github.actforever.kuudra.api.EventSource.class,
                Map.of("intervalMillis", 250));
        manager.close();
        assertEquals(List.of("component.initialize", "component.start", "component.component.initialize", "component.component.destroy", "component.stop", "component.destroy"), calls);
    }

    @Test
    void exposesStructuredPluginViewsAndIdentityBoundLogger() {
        AtomicReference<SystemEvent> logged = new AtomicReference<>();
        SystemEventBus bus = new SystemEventBus() {
            @Override public AutoCloseable subscribe(Consumer<SystemEvent> listener) { return () -> { }; }
            @Override public void publish(SystemEvent event) { if (event.type().equals("plugin.log")) logged.set(event); }
        };
        PluginComponentDocumentation documentation = new PluginComponentDocumentation(
                "periodically emits greetings", "intervalMillis: 1000", true, List.of("start", "stop"),
                List.of(new PluginEventDocumentation("scheduled tick", "hello.tick", "greeting", "{message: hello}")));
        AtomicReference<Path> home = new AtomicReference<>();
        DefaultPluginManager manager = new DefaultPluginManager(temporaryDirectory.resolve("plugin-views"),
                PluginRuntimeServices.unavailable(), bus);
        manager.register(new PluginArchiveLoader.LoadedPlugin(
                new PluginMetadata("sample", "demo", "1.2.3", "example.Plugin", List.of()),
                new RecordingPlugin("sample", List.of(), new ArrayList<>()) {
                    @Override public CompletionStage<Void> initialize(PluginContext context) {
                        home.set(context.home()); context.logger().info("initialized", Map.of("ready", true));
                        return CompletableFuture.completedFuture(null);
                    }
                },
                List.of(new PluginComponentDefinition("sample", "demo", PluginComponentKind.EVENT_SOURCE,
                        "source", TestSource.class, ComponentInstancePolicy.DEFAULT, documentation))));
        manager.startAll().toCompletableFuture().join();

        assertEquals(temporaryDirectory.resolve("plugin-views/demo/sample").toAbsolutePath().normalize(), home.get());
        assertEquals("demo", logged.get().data().get("namespace"));
        assertEquals("sample", logged.get().data().get("pluginId"));
        DefaultPluginManager.PluginView plugin = manager.pluginView("sample");
        assertEquals("1.2.3", plugin.version());
        assertEquals("periodically emits greetings", plugin.components().get(0).documentation().purpose());
        assertEquals("hello.tick", plugin.components().get(0).documentation().emittedEvents().get(0).eventType());
        manager.close();
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

    public static final class ManagedTestSource implements io.github.actforever.kuudra.api.EventSource, PluginComponentLifecycle {
        static List<String> calls;
        @Override public void setEmitter(io.github.actforever.kuudra.api.EventEmitter emitter) { }
        @Override public CompletionStage<Void> start() { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> initialize(PluginComponentContext context) {
            assertTrue(Files.isDirectory(context.plugin().home()));
            assertEquals(250, context.configuration("intervalMillis", Integer.class));
            calls.add("component.component.initialize");
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> destroy() {
            calls.add("component.component.destroy");
            return CompletableFuture.completedFuture(null);
        }
    }
}
