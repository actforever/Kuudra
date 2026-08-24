package io.github.actforever.kuudra.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;

/**
 * Dependency-aware lifecycle manager for already loaded plugins.
 *
 * <p>Fat-JAR discovery and isolated ClassLoaders stay outside this first
 * kernel; this class deliberately owns only ordering, per-plugin homes and
 * lifecycle failure visibility.</p>
 */
public final class DefaultPluginManager implements AutoCloseable {
    private final Path pluginsHome;
    private final PluginRuntimeServices runtimeServices;
    private final SystemEventBus events;
    private final Map<String, KuudraPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, PluginState> states = new LinkedHashMap<>();
    private final Map<String, ManagedResources> resources = new LinkedHashMap<>();
    private final Map<String, PluginContext> contexts = new LinkedHashMap<>();
    private final Map<String, String> namespaces = new LinkedHashMap<>();
    private final Map<String, String> versions = new LinkedHashMap<>();
    private final List<PluginComponentLifecycle> managedComponents = new ArrayList<>();
    private final Map<String, List<String>> dependencies = new LinkedHashMap<>();
    private final Map<String, List<PluginDependency>> dependencyMetadata = new LinkedHashMap<>();
    private final PluginComponentRegistry componentRegistry = new PluginComponentRegistry();
    private List<String> startedOrder = List.of();

    public DefaultPluginManager(Path pluginsHome) {
        this(pluginsHome, PluginRuntimeServices.unavailable(), noEvents());
    }

    public DefaultPluginManager(Path pluginsHome, PluginRuntimeServices runtimeServices) {
        this(pluginsHome, runtimeServices, noEvents());
    }

    public DefaultPluginManager(Path pluginsHome, PluginRuntimeServices runtimeServices, SystemEventBus events) {
        this.pluginsHome = Objects.requireNonNull(pluginsHome, "pluginsHome").toAbsolutePath().normalize();
        this.runtimeServices = Objects.requireNonNull(runtimeServices, "runtimeServices");
        this.events = Objects.requireNonNull(events, "events");
    }

    public synchronized void register(KuudraPlugin plugin) {
        List<PluginDependency> declared = plugin.descriptor().requires().stream()
                .map(id -> new PluginDependency(id, id, true, "[0,)" )).toList();
        register(plugin, plugin.descriptor().requires(), declared, List.of(), plugin.id(), "unspecified");
    }

    /** Register a plugin loaded from metadata.toml; metadata dependencies are authoritative. */
    public synchronized void register(PluginArchiveLoader.LoadedPlugin loaded) {
        Objects.requireNonNull(loaded, "loaded");
        if (!loaded.instance().id().equals(loaded.metadata().id())) throw new IllegalArgumentException("Plugin id and metadata id must match");
        List<String> ordering = loaded.metadata().dependencies().stream()
                .filter(dependency -> dependency.mandatory() || plugins.containsKey(dependency.pluginId()))
                .map(PluginDependency::pluginId).toList();
        register(loaded.instance(), ordering, loaded.metadata().dependencies(), loaded.components(), loaded.metadata().namespace(), loaded.metadata().version());
    }

    private void register(KuudraPlugin plugin, List<String> required, List<PluginDependency> declaredDependencies,
                          List<PluginComponentDefinition> components, String namespace, String version) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor = plugin.descriptor();
        if (!descriptor.id().equals(plugin.id())) {
            throw new IllegalArgumentException("Plugin id and descriptor id must match");
        }
        if (plugins.containsKey(plugin.id())) {
            throw new IllegalArgumentException("Plugin already registered: " + plugin.id());
        }
        plugins.put(plugin.id(), plugin);
        namespaces.put(plugin.id(), namespace);
        versions.put(plugin.id(), version);
        dependencies.put(plugin.id(), List.copyOf(required));
        dependencyMetadata.put(plugin.id(), List.copyOf(declaredDependencies));
        states.put(plugin.id(), PluginState.REGISTERED);
        resources.put(plugin.id(), new ManagedResources());
        components.forEach(componentRegistry::register);
        event("plugin.registered", Map.of("pluginId", plugin.id(), "namespace", namespace, "dependencies", List.copyOf(required), "components", components.size()));
    }

    public synchronized PluginState state(String pluginId) {
        PluginState state = states.get(pluginId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown plugin: " + pluginId);
        }
        return state;
    }

    public synchronized Map<String, PluginState> states() {
        return Map.copyOf(states);
    }
    public synchronized List<PluginView> pluginViews() {
        return plugins.keySet().stream().map(this::pluginView).toList();
    }
    public synchronized PluginView pluginView(String pluginId) {
        if (!plugins.containsKey(pluginId)) throw new IllegalArgumentException("Unknown plugin: " + pluginId);
        List<ComponentView> components = componentRegistry.definitions().values().stream()
                .filter(component -> component.pluginId().equals(pluginId)).map(DefaultPluginManager::view).toList();
        return new PluginView(pluginId, namespaces.get(pluginId), versions.get(pluginId), states.get(pluginId),
                dependencyMetadata.get(pluginId), components);
    }
    public synchronized List<ComponentView> componentViews() {
        return componentRegistry.definitions().values().stream().map(DefaultPluginManager::view).toList();
    }
    public synchronized ComponentView componentView(String reference) {
        return view(componentRegistry.find(reference).orElseThrow(() -> new IllegalArgumentException("Unknown component: " + reference)));
    }
    private static ComponentView view(PluginComponentDefinition definition) {
        return new ComponentView(definition.reference(), definition.pluginId(), definition.namespace(), definition.kind(),
                definition.name(), definition.implementation().getName(), definition.instancePolicy(), definition.documentation());
    }
    public record PluginView(String id, String namespace, String version, PluginState state,
                             List<PluginDependency> dependencies, List<ComponentView> components) {
        public PluginView { dependencies = List.copyOf(dependencies); components = List.copyOf(components); }
    }
    public record ComponentView(String reference, String pluginId, String namespace, PluginComponentKind kind,
                                String name, String implementation, ComponentInstancePolicy instancePolicy,
                                PluginComponentDocumentation documentation) { }
    public PluginComponentRegistry components() { return componentRegistry; }

    /** Creates and initializes a Flow-owned component after its plugin is active. */
    public <T> T createComponent(String reference, Class<T> expectedType) {
        return createComponent(reference, expectedType, Map.of());
    }

    /** Creates a component and exposes its immutable manifest options during component initialization. */
    public <T> T createComponent(String reference, Class<T> expectedType, Map<String, Object> configuration) {
        PluginComponentDefinition definition = componentRegistry.find(reference)
                .orElseThrow(() -> new IllegalArgumentException("Unknown component: " + reference));
        final PluginContext context;
        synchronized (this) {
            if (states.get(definition.pluginId()) != PluginState.ACTIVE) {
                throw new IllegalStateException("Plugin is not active for component " + reference);
            }
            context = contexts.get(definition.pluginId());
        }
        T instance = componentRegistry.create(reference, expectedType);
        event("plugin.component.created", Map.of("pluginId", definition.pluginId(), "component", reference));
        if (instance instanceof PluginComponentLifecycle lifecycle) {
            try {
                event("plugin.component.initializing", Map.of("pluginId", definition.pluginId(), "component", reference));
                lifecycle.initialize(new PluginComponentContext(reference, context, configuration)).toCompletableFuture().join();
                synchronized (this) { managedComponents.add(lifecycle); }
                event("plugin.component.initialized", Map.of("pluginId", definition.pluginId(), "component", reference));
            } catch (RuntimeException error) {
                event("plugin.component.failed", Map.of("pluginId", definition.pluginId(), "component", reference, "error", error.toString()));
                throw new IllegalStateException("Failed to initialize component " + reference, error);
            }
        }
        return instance;
    }

    public CompletionStage<Void> startAll() {
        final List<String> order;
        synchronized (this) {
            order = dependencyOrder();
        }
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (String pluginId : order) {
            chain = chain.thenCompose(ignored -> initializeAndStart(pluginId))
                    .thenRun(() -> recordStarted(pluginId));
        }
        return chain;
    }

    public CompletionStage<Void> stopAll() {
        final List<String> reverse;
        synchronized (this) {
            reverse = new ArrayList<>(startedOrder);
        }
        Collections.reverse(reverse);
        CompletionStage<Void> chain = destroyComponents();
        for (String pluginId : reverse) {
            chain = chain.thenCompose(ignored -> stopAndDestroy(pluginId));
        }
        return chain.thenRun(() -> {
            synchronized (this) {
                startedOrder = List.of();
            }
        });
    }

    private CompletionStage<Void> initializeAndStart(String pluginId) {
        final KuudraPlugin plugin;
        final Path home;
        synchronized (this) {
            plugin = plugins.get(pluginId);
            if (states.get(pluginId) == PluginState.ACTIVE) {
                return CompletableFuture.completedFuture(null);
            }
            home = pluginsHome.resolve(namespaces.get(pluginId)).resolve(pluginId).normalize();
            if (!home.startsWith(pluginsHome)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid plugin id path: " + pluginId));
            }
        }
        try {
            Files.createDirectories(home);
        } catch (IOException exception) {
            markFailed(pluginId);
            return CompletableFuture.failedFuture(exception);
        }
        String namespace = namespaces.get(pluginId);
        PluginContext context = new PluginContext(pluginId, namespace, home, resources.get(pluginId), runtimeServices,
                pluginLogger(pluginId, namespace));
        event("plugin.initializing", Map.of("pluginId", pluginId, "home", home.toString()));
        return invoke(plugin, current -> current.initialize(context))
                .thenRun(() -> { synchronized (this) { contexts.put(pluginId, context); } })
                .thenRun(() -> { mark(pluginId, PluginState.INITIALIZED); event("plugin.initialized", Map.of("pluginId", pluginId)); })
                .thenRun(() -> event("plugin.starting", Map.of("pluginId", pluginId)))
                .thenCompose(ignored -> invoke(plugin, KuudraPlugin::start))
                .thenRun(() -> { mark(pluginId, PluginState.ACTIVE); event("plugin.active", Map.of("pluginId", pluginId)); })
                .exceptionallyCompose(error -> cleanupFailedStart(pluginId, plugin, error));
    }

    private CompletionStage<Void> stopAndDestroy(String pluginId) {
        final KuudraPlugin plugin;
        synchronized (this) {
            plugin = plugins.get(pluginId);
            if (states.get(pluginId) != PluginState.ACTIVE) {
                return CompletableFuture.completedFuture(null);
            }
        }
        event("plugin.stopping", Map.of("pluginId", pluginId));
        return invoke(plugin, KuudraPlugin::stop)
                .thenCompose(ignored -> invoke(plugin, KuudraPlugin::destroy))
                .thenRun(() -> closeResources(pluginId))
                .thenRun(() -> { mark(pluginId, PluginState.STOPPED); event("plugin.stopped", Map.of("pluginId", pluginId)); })
                .exceptionallyCompose(error -> failed(pluginId, error));
    }

    private CompletionStage<Void> invoke(KuudraPlugin plugin, Function<KuudraPlugin, CompletionStage<Void>> operation) {
        try {
            CompletionStage<Void> stage = operation.apply(plugin);
            return stage == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("Plugin lifecycle operation returned null"))
                    : stage;
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private synchronized void mark(String pluginId, PluginState state) {
        states.put(pluginId, state);
    }

    private synchronized void markFailed(String pluginId) {
        states.put(pluginId, PluginState.FAILED);
    }

    private CompletionStage<Void> failed(String pluginId, Throwable error) {
        markFailed(pluginId);
        event("plugin.failed", Map.of("pluginId", pluginId, "error", error.toString()));
        return CompletableFuture.failedFuture(error);
    }

    private CompletionStage<Void> cleanupFailedStart(String pluginId, KuudraPlugin plugin, Throwable failure) {
        return invoke(plugin, KuudraPlugin::destroy).handle((ignored, destroyError) -> {
            if (destroyError != null) failure.addSuppressed(destroyError);
            try { closeResources(pluginId); }
            catch (RuntimeException resourceError) { failure.addSuppressed(resourceError); }
            markFailed(pluginId);
            event("plugin.failed", Map.of("pluginId", pluginId, "error", failure.toString()));
            return null;
        }).thenCompose(ignored -> CompletableFuture.failedFuture(failure));
    }

    private synchronized void recordStarted(String pluginId) {
        if (startedOrder.contains(pluginId)) return;
        List<String> updated = new ArrayList<>(startedOrder);
        updated.add(pluginId);
        startedOrder = List.copyOf(updated);
    }

    private void closeResources(String pluginId) {
        resources.get(pluginId).closeAll();
    }

    private List<String> dependencyOrder() {
        List<String> order = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String pluginId : plugins.keySet()) {
            visit(pluginId, visited, visiting, order);
        }
        return order;
    }

    private void visit(String pluginId, Set<String> visited, Set<String> visiting, Collection<String> order) {
        if (visited.contains(pluginId)) {
            return;
        }
        if (!visiting.add(pluginId)) {
            throw new IllegalStateException("Plugin dependency cycle: " + visiting);
        }
        KuudraPlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            throw new IllegalStateException("Required plugin is not registered: " + pluginId);
        }
        for (String dependency : dependencies.get(pluginId)) {
            visit(dependency, visited, visiting, order);
        }
        visiting.remove(pluginId);
        visited.add(pluginId);
        order.add(pluginId);
    }

    @Override
    public void close() {
        stopAll().toCompletableFuture().join();
    }

    private CompletionStage<Void> destroyComponents() {
        final List<PluginComponentLifecycle> reverse;
        synchronized (this) {
            reverse = new ArrayList<>(managedComponents);
            Collections.reverse(reverse);
            managedComponents.clear();
        }
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (PluginComponentLifecycle component : reverse) {
            chain = chain.thenCompose(ignored -> {
                try {
                    event("plugin.component.destroying", Map.of("componentClass", component.getClass().getName()));
                    return component.destroy().thenRun(() -> event("plugin.component.destroyed", Map.of("componentClass", component.getClass().getName())));
                }
                catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
            });
        }
        return chain;
    }

    private void event(String type, Map<String, Object> data) { events.publish(SystemEvent.of(type, data)); }
    private PluginLogger pluginLogger(String pluginId, String namespace) {
        return (level, message, fields, error) -> {
            if (message == null || message.isBlank()) throw new IllegalArgumentException("plugin log message must not be blank");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pluginId", pluginId); data.put("namespace", namespace); data.put("level", level.name());
            data.put("message", message); data.put("fields", Map.copyOf(fields));
            if (error != null) data.put("error", error.toString());
            event("plugin.log", data);
        };
    }
    private static SystemEventBus noEvents() {
        return new SystemEventBus() {
            @Override public AutoCloseable subscribe(java.util.function.Consumer<SystemEvent> listener) { return () -> { }; }
            @Override public void publish(SystemEvent event) { }
        };
    }

    private static final class ManagedResources implements PluginResourceRegistry {
        private final LinkedHashMap<String, AutoCloseable> resources = new LinkedHashMap<>();

        @Override
        public synchronized void register(String name, AutoCloseable resource) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(resource, "resource");
            if (name.isBlank() || resources.putIfAbsent(name, resource) != null) {
                throw new IllegalArgumentException("Duplicate or blank plugin resource: " + name);
            }
        }

        @Override
        public synchronized List<String> names() {
            return List.copyOf(resources.keySet());
        }

        private synchronized void closeAll() {
            List<AutoCloseable> closeOrder = new ArrayList<>(resources.values());
            Collections.reverse(closeOrder);
            resources.clear();
            RuntimeException failure = null;
            for (AutoCloseable resource : closeOrder) {
                try {
                    resource.close();
                } catch (Exception error) {
                    if (failure == null) failure = new IllegalStateException("Failed to close plugin resource", error);
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
