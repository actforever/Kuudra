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

import io.github.actforever.kuudra.api.system.SystemEvent;
import io.github.actforever.kuudra.api.system.SystemEventPublisher;

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
    private final SystemEventPublisher events;
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
    private final ResourceTemplateRegistry resourceTemplateRegistry = new ResourceTemplateRegistry();
    private List<String> startedOrder = List.of();

    public DefaultPluginManager(Path pluginsHome) {
        this(pluginsHome, PluginRuntimeServices.unavailable(), noEvents());
    }

    public DefaultPluginManager(Path pluginsHome, PluginRuntimeServices runtimeServices) {
        this(pluginsHome, runtimeServices, noEvents());
    }

    public DefaultPluginManager(Path pluginsHome, PluginRuntimeServices runtimeServices, SystemEventPublisher events) {
        this.pluginsHome = Objects.requireNonNull(pluginsHome, "pluginsHome").toAbsolutePath().normalize();
        this.runtimeServices = Objects.requireNonNull(runtimeServices, "runtimeServices");
        this.events = Objects.requireNonNull(events, "events");
    }

    public synchronized void register(KuudraPlugin plugin) {
        List<PluginDependency> declared = plugin.descriptor().requires().stream()
                .map(id -> new PluginDependency(id, id, true, "[0,)")).toList();
        register(plugin, declared.stream().map(PluginDependency::identity).toList(), declared,
                List.of(), List.of(), plugin.id(), "unspecified");
    }

    /**
     * Register a plugin loaded from metadata.toml; metadata dependencies are authoritative.
     */
    public synchronized void register(PluginArchiveLoader.LoadedPlugin loaded) {
        Objects.requireNonNull(loaded, "loaded");
        if (!loaded.instance().id().equals(loaded.metadata().id()))
            throw new IllegalArgumentException("Plugin id and metadata id must match");
        List<String> ordering = loaded.metadata().dependencies().stream()
                .filter(dependency -> dependency.mandatory() || plugins.containsKey(dependency.identity()))
                .map(PluginDependency::identity).toList();
        register(loaded.instance(), ordering, loaded.metadata().dependencies(), List.of(),
                loaded.resourceTemplates(), loaded.metadata().namespace(), loaded.metadata().version());
    }

    private void register(KuudraPlugin plugin, List<String> required, List<PluginDependency> declaredDependencies,
                          List<PluginComponentDefinition> components,
                          List<ResourceTemplateDefinition> resourceTemplates,
                          String namespace, String version) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor = plugin.descriptor();
        if (!descriptor.id().equals(plugin.id())) {
            throw new IllegalArgumentException("Plugin id and descriptor id must match");
        }
        String identity = identity(namespace, plugin.id());
        if (plugins.containsKey(identity)) {
            throw new IllegalArgumentException("Plugin already registered: " + identity);
        }
        plugins.put(identity, plugin);
        namespaces.put(identity, namespace);
        versions.put(identity, version);
        dependencies.put(identity, List.copyOf(required));
        dependencyMetadata.put(identity, List.copyOf(declaredDependencies));
        states.put(identity, PluginState.REGISTERED);
        resources.put(identity, new ManagedResources());
        components.forEach(componentRegistry::register);
        resourceTemplates.forEach(resourceTemplateRegistry::register);
        debugEvent("plugin.dependencies.resolved", Map.of("pluginId", plugin.id(), "namespace", namespace,
                "required", List.copyOf(required), "declared", declaredDependencies.size()));
        debugEvent("plugin.registered", Map.of("pluginId", plugin.id(), "namespace", namespace,
                "dependencies", List.copyOf(required), "resourceTemplates", resourceTemplates.size()));
    }

    public synchronized PluginState state(String namespace, String pluginId) {
        PluginState state = states.get(identity(namespace, pluginId));
        if (state == null) {
            throw new IllegalArgumentException("Unknown plugin: " + identity(namespace, pluginId));
        }
        return state;
    }

    public synchronized Map<String, PluginState> states() {
        return Map.copyOf(states);
    }

    public synchronized List<PluginView> pluginViews() {
        return plugins.keySet().stream().map(this::pluginView).toList();
    }

    public synchronized PluginView pluginView(String namespace, String pluginId) {
        return pluginView(identity(namespace, pluginId));
    }

    private synchronized PluginView pluginView(String identity) {
        if (!plugins.containsKey(identity)) throw new IllegalArgumentException("Unknown plugin: " + identity);
        String pluginId = plugins.get(identity).id();
        List<ComponentView> components = componentRegistry.definitions().values().stream()
                .filter(component -> component.pluginId().equals(pluginId) && component.namespace().equals(namespaces.get(identity)))
                .map(DefaultPluginManager::view).toList();
        List<ResourceTemplateView> templates = resourceTemplateRegistry.definitions().values().stream()
                .filter(template -> template.pluginId().equals(pluginId)
                        && template.namespace().equals(namespaces.get(identity)))
                .map(DefaultPluginManager::view).toList();
        return new PluginView(pluginId, namespaces.get(identity), versions.get(identity), states.get(identity),
                dependencyMetadata.get(identity), components, templates);
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
                             List<PluginDependency> dependencies, List<ComponentView> components,
                             List<ResourceTemplateView> resourceTemplates) {
        public PluginView {
            dependencies = List.copyOf(dependencies);
            components = List.copyOf(components);
            resourceTemplates = List.copyOf(resourceTemplates);
        }
    }

    public record ComponentView(String reference, String pluginId, String namespace, PluginComponentKind kind,
                                String name, String implementation, ComponentInstancePolicy instancePolicy,
                                PluginComponentDocumentation documentation) {
    }

    public PluginComponentRegistry components() {
        return componentRegistry;
    }

    public ResourceTemplateRegistry resourceTemplates() {
        return resourceTemplateRegistry;
    }

    public synchronized List<ResourceTemplateView> resourceTemplateViews() {
        return resourceTemplateRegistry.definitions().values().stream()
                .map(DefaultPluginManager::view).toList();
    }

    public synchronized ResourceTemplateView resourceTemplateView(String reference) {
        return view(resourceTemplateRegistry.find(reference).orElseThrow(
                () -> new IllegalArgumentException("Unknown ResourceTemplate: " + reference)));
    }

    private static ResourceTemplateView view(ResourceTemplateDefinition definition) {
        return new ResourceTemplateView(definition.reference(), definition.pluginId(), definition.namespace(),
                definition.kind(), definition.name(), definition.implementation().getName(), definition.policy(),
                definition.documentation(), definition.handlers().stream().map(handler -> new HandlerView(
                        handler.name(), handler.purpose(), handler.arguments(), handler.emittedEvents())).toList());
    }

    public record ResourceTemplateView(String reference, String pluginId, String namespace,
                                       ResourceTemplateKind kind, String name, String implementation,
                                       ResourcePolicy policy, ResourceTemplateDocumentation documentation,
                                       List<HandlerView> handlers) {
        public ResourceTemplateView { handlers = List.copyOf(handlers); }
    }

    public record HandlerView(String name, String purpose,
                              List<PluginConfigurationDocumentation> arguments,
                              List<PluginEventDocumentation> emittedEvents) {
        public HandlerView {
            arguments = List.copyOf(arguments);
            emittedEvents = List.copyOf(emittedEvents);
        }
    }

    /** Materializes one App-owned resource; lifecycle remains owned by App reconciliation. */
    public <T> T createResource(String templateReference, Class<T> expectedType) {
        ResourceTemplateDefinition definition = resourceTemplateRegistry.find(templateReference)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ResourceTemplate: " + templateReference));
        synchronized (this) {
            String identity = identity(definition.namespace(), definition.pluginId());
            if (states.get(identity) != PluginState.ACTIVE) {
                throw new IllegalStateException("Plugin is not active for ResourceTemplate " + templateReference);
            }
        }
        Object instance = resourceTemplateRegistry.create(templateReference);
        if (!expectedType.isInstance(instance)) {
            throw new IllegalArgumentException("ResourceTemplate " + templateReference + " is not a "
                    + expectedType.getName());
        }
        debugEvent("plugin.resource.created", Map.of("pluginId", definition.pluginId(),
                "resourceTemplate", templateReference, "instanceClass", instance.getClass().getName()));
        return expectedType.cast(instance);
    }

    public ResourceContext resourceContext(String templateReference, String resourceReference,
                                           Map<String, Object> options) {
        ResourceTemplateDefinition definition = resourceTemplateRegistry.find(templateReference)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ResourceTemplate: " + templateReference));
        synchronized (this) {
            PluginContext context = contexts.get(identity(definition.namespace(), definition.pluginId()));
            if (context == null) throw new IllegalStateException("Plugin context is unavailable: " + templateReference);
            return new ResourceContext(resourceReference, context, options);
        }
    }

    /**
     * Creates and initializes a Flow-owned component after its plugin is active.
     */
    public <T> T createComponent(String reference, Class<T> expectedType) {
        return createComponent(reference, expectedType, Map.of());
    }

    /**
     * Creates a component and exposes its immutable manifest options during component initialization.
     */
    public <T> T createComponent(String reference, Class<T> expectedType, Map<String, Object> configuration) {
        PluginComponentDefinition definition = componentRegistry.find(reference)
                .orElseThrow(() -> new IllegalArgumentException("Unknown component: " + reference));
        final PluginContext context;
        synchronized (this) {
            String identity = identity(definition.namespace(), definition.pluginId());
            if (states.get(identity) != PluginState.ACTIVE) {
                throw new IllegalStateException("Plugin is not active for component " + reference);
            }
            context = contexts.get(identity);
        }
        T instance = componentRegistry.create(reference, expectedType);
        debugEvent("plugin.component.created", Map.of("pluginId", definition.pluginId(), "component", reference,
                "instanceClass", instance.getClass().getName()));
        if (instance instanceof PluginComponentLifecycle lifecycle) {
            try {
                debugEvent("plugin.component.initializing", Map.of("pluginId", definition.pluginId(), "component", reference));
                lifecycle.initialize(new PluginComponentContext(reference, context, configuration)).toCompletableFuture().join();
                synchronized (this) {
                    managedComponents.add(lifecycle);
                }
                debugEvent("plugin.component.initialized", Map.of("pluginId", definition.pluginId(), "component", reference));
            } catch (RuntimeException error) {
                errorEvent("plugin.component.failed", Map.of("pluginId", definition.pluginId(), "component", reference, "error", error.toString()));
                throw new IllegalStateException("Failed to initialize component " + reference, error);
            }
        }
        return instance;
    }

    /**
     * Destroys one App-owned component instance during reconciliation.
     */
    public CompletionStage<Void> destroyComponent(Object instance) {
        if (!(instance instanceof PluginComponentLifecycle lifecycle)) return CompletableFuture.completedFuture(null);
        synchronized (this) {
            managedComponents.remove(lifecycle);
        }
        return lifecycle.destroy();
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
            home = pluginsHome.resolve(namespaces.get(pluginId)).resolve(plugin.id()).normalize();
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
        String declaredId = plugin.id();
        PluginContext context = new PluginContext(declaredId, namespace, home, resources.get(pluginId), runtimeServices,
                pluginLogger(declaredId, namespace));
        debugEvent("plugin.initializing", Map.of("pluginId", declaredId, "namespace", namespace, "home", home.toString()));
        return invoke(plugin, current -> current.initialize(context))
                .thenRun(() -> {
                    synchronized (this) {
                        contexts.put(pluginId, context);
                    }
                })
                .thenRun(() -> {
                    mark(pluginId, PluginState.INITIALIZED);
                    debugEvent("plugin.initialized", Map.of("pluginId", declaredId, "namespace", namespace));
                })
                .thenRun(() -> debugEvent("plugin.starting", Map.of("pluginId", declaredId, "namespace", namespace)))
                .thenCompose(ignored -> invoke(plugin, KuudraPlugin::start))
                .thenRun(() -> {
                    mark(pluginId, PluginState.ACTIVE);
                    event("plugin.active", Map.of("pluginId", declaredId, "namespace", namespace));
                })
                .exceptionallyCompose(error -> cleanupFailedStart(pluginId, plugin, error));
    }

    private CompletionStage<Void> stopAndDestroy(String pluginId) {
        final KuudraPlugin plugin;
        final String namespace;
        synchronized (this) {
            plugin = plugins.get(pluginId);
            namespace = namespaces.get(pluginId);
            if (states.get(pluginId) != PluginState.ACTIVE) {
                return CompletableFuture.completedFuture(null);
            }
        }
        debugEvent("plugin.stopping", Map.of("pluginId", plugin.id(), "namespace", namespace));
        return invoke(plugin, KuudraPlugin::stop)
                .thenCompose(ignored -> invoke(plugin, KuudraPlugin::destroy))
                .thenRun(() -> closeResources(pluginId))
                .thenRun(() -> {
                    mark(pluginId, PluginState.STOPPED);
                    debugEvent("plugin.stopped", Map.of("pluginId", plugin.id(), "namespace", namespace));
                })
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
        errorEvent("plugin.failed", pluginEvent(pluginId, Map.of("error", error.toString())));
        return CompletableFuture.failedFuture(error);
    }

    private CompletionStage<Void> cleanupFailedStart(String pluginId, KuudraPlugin plugin, Throwable failure) {
        return invoke(plugin, KuudraPlugin::destroy).handle((ignored, destroyError) -> {
            if (destroyError != null) failure.addSuppressed(destroyError);
            try {
                closeResources(pluginId);
            } catch (RuntimeException resourceError) {
                failure.addSuppressed(resourceError);
            }
            markFailed(pluginId);
            errorEvent("plugin.failed", pluginEvent(pluginId, Map.of("error", failure.toString())));
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

    private static String identity(String namespace, String pluginId) {
        return namespace + "/" + pluginId;
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
                    debugEvent("plugin.component.destroying", Map.of("componentClass", component.getClass().getName()));
                    return component.destroy().thenRun(() -> debugEvent("plugin.component.destroyed", Map.of("componentClass", component.getClass().getName())));
                } catch (RuntimeException error) {
                    return CompletableFuture.failedFuture(error);
                }
            });
        }
        return chain;
    }

    private void event(String type, Map<String, Object> data) {
        events.publish(SystemEvent.of(type, data));
    }

    private void debugEvent(String type, Map<String, Object> data) {
        events.publish(SystemEvent.debug(type, data));
    }

    private void errorEvent(String type, Map<String, Object> data) {
        events.publish(SystemEvent.error(type, data));
    }

    private synchronized Map<String, Object> pluginEvent(String identity, Map<String, Object> details) {
        Map<String, Object> data = new LinkedHashMap<>(details);
        data.put("pluginId", plugins.get(identity).id());
        data.put("namespace", namespaces.get(identity));
        return Map.copyOf(data);
    }

    private PluginLogger pluginLogger(String pluginId, String namespace) {
        return (level, message, fields, error) -> {
            if (message == null || message.isBlank())
                throw new IllegalArgumentException("plugin log message must not be blank");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pluginId", pluginId);
            data.put("namespace", namespace);
            data.put("level", level.name());
            data.put("message", message);
            data.put("fields", Map.copyOf(fields));
            if (error != null) data.put("error", error.toString());
            event("plugin.log", data);
        };
    }

    private static SystemEventPublisher noEvents() {
        return SystemEventPublisher.noop();
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
