package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.app.AppLifecycle;
import io.github.actforever.kuudra.api.app.AppSnapshot;
import io.github.actforever.kuudra.api.app.AppStatus;
import io.github.actforever.kuudra.api.*;
import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;
import io.github.actforever.kuudra.api.runtime.FlowSnapshot;
import io.github.actforever.kuudra.api.session.SessionSnapshot;
import io.github.actforever.kuudra.api.component.SourceRegistration;
import io.github.actforever.kuudra.api.system.SystemEvent;
import io.github.actforever.kuudra.api.system.SystemEventBus;
import io.github.actforever.kuudra.plugin.DefaultPluginManager;
import io.github.actforever.kuudra.plugin.PluginArchiveLoader;
import io.github.actforever.kuudra.plugin.PluginComponentRegistry;
import io.github.actforever.kuudra.plugin.PluginComponentDefinition;
import io.github.actforever.kuudra.plugin.ComponentLimitScope;
import io.github.actforever.kuudra.config.KuudraConfig;
import io.github.actforever.kuudra.config.KuudraManifest;
import io.github.actforever.kuudra.config.KuudraConfigResource;
import io.github.actforever.kuudra.config.KuudraYamlLoader;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;
import io.github.actforever.kuudra.logging.KuudraLog;
import io.github.actforever.kuudra.logging.KuudraLogConfiguration;
import io.github.actforever.kuudra.logging.KuudraLogLevel;
import io.github.actforever.kuudra.logging.KuudraLogSession;
import io.github.actforever.kuudra.i18n.MessageResolver;
import io.github.actforever.kuudra.i18n.MessageResolvers;
import io.github.actforever.kuudra.i18n.PluginMessageCatalogs;
import io.github.actforever.kuudra.plugin.KernelControlAction;
import io.github.actforever.kuudra.plugin.PluginRuntimeServices;
import io.github.actforever.kuudra.state.ResourceStateStore;
import io.github.actforever.kuudra.state.SqliteResourceStateStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;
import java.util.LinkedHashMap;

/** Framework-independent App facade. Its lifecycle owns a Runtime but not any HTTP/Web/TUI adapter. */
public final class KuudraApp implements AutoCloseable, AppLifecycle {
    private static final ResourceDocumentation FLOW_DOCUMENTATION = new ResourceDocumentation(
            "kuudra.io/v1alpha1", "kuudra-official", "Flow",
            "Declares an immutable routing graph by importing resources and connecting import aliases.",
            List.of(
                    new ResourceFieldDocumentation("metadata.namespace", "String", true,
                            "Resource namespace. Unqualified imports default to this namespace.", List.of("dev")),
                    new ResourceFieldDocumentation("metadata.name", "String", true,
                            "Flow resource name.", List.of("flow-1")),
                    new ResourceFieldDocumentation("spec.imports", "Map<String, ResourceReference>", true,
                            "Import alias map. Each value declares kind and name; namespace is optional.", List.of(Map.of(
                                    "source", Map.of("kind", "EventSource", "name", "mysource"),
                                    "handler", Map.of("kind", "EventHandler", "name", "myhandler")))),
                    new ResourceFieldDocumentation("spec.edges", "List<Edge>", true,
                            "Directed edges whose from/to values reference keys in spec.imports.",
                            List.of(List.of(Map.of("from", "source", "to", "handler"))))),
            List.of(Map.of(
                    "apiVersion", "kuudra.io/v1alpha1", "kind", "Flow",
                    "metadata", Map.of("namespace", "dev", "name", "flow-1"),
                    "spec", Map.of(
                            "imports", Map.of(
                                    "source", Map.of("kind", "EventSource", "name", "mysource"),
                                    "handler", Map.of("kind", "EventHandler", "name", "myhandler")),
                            "edges", List.of(Map.of("from", "source", "to", "handler"))))));
    private final int queueCapacity;
    private final int workerThreads;
    private final KuudraConfig.RuntimeConfig bootstrapConfig;
    private final List<HomeInitialization> homeInitializations;
    private boolean homeInitializationReported;
    private Map<String, Object> globalContext = Map.of();
    private final SystemEventBus events = new AppSystemEventBus();
    private volatile MessageResolver externalMessageResolver = MessageResolver.none();
    private volatile MessageResolver configuredMessageResolver = MessageResolvers.english();
    private volatile PluginMessageCatalogs pluginMessageCatalogs = new PluginMessageCatalogs(MessageResolvers.DEFAULT_LOCALE);
    private final MessageResolver messageResolver = (key, arguments) ->
            externalMessageResolver.resolve(key, arguments)
                    .or(() -> pluginMessageCatalogs.resolve(key, arguments))
                    .or(() -> configuredMessageResolver.resolve(key, arguments));
    private final List<PluginArchiveLoader.LoadedArchive> archives = new ArrayList<>();
    private final Map<ResourceKey, ManagedEventSource> eventSources = new LinkedHashMap<>();
    private final Map<KuudraManifest.ResourceId, Object> manifestInstances = new LinkedHashMap<>();
    private final Map<KuudraManifest.ResourceId, SourceRegistration> manifestSourceRegistrations = new LinkedHashMap<>();
    private final Map<KuudraManifest.ResourceId, List<KuudraRuntime.SourceTarget>> manifestSourceTargets = new LinkedHashMap<>();
    private final Map<KuudraManifest.ResourceId, String> manifestObservedStates = new LinkedHashMap<>();
    private KuudraManifest.Resources manifestResources = KuudraManifest.Resources.EMPTY;
    private KuudraConfig.ResourceSelectionSettings resourceSelection = new KuudraConfig.ResourceSelectionSettings(
            KuudraConfig.NamespaceMode.ALL, java.util.Set.of());
    private KuudraRuntime runtime;
    private DefaultPluginManager plugins;
    private KuudraLogSession logSession;
    private ResourceStateStore stateStore;
    private java.util.concurrent.ScheduledExecutorService reconciliationExecutor;
    private KernelCheckpoint checkpoint;
    private AppStatus status = AppStatus.CREATED;
    private String detail = "not started";

    public KuudraApp(int queueCapacity, int workerThreads) { this(queueCapacity, workerThreads, null); }
    private KuudraApp(int queueCapacity, int workerThreads, KuudraConfig.RuntimeConfig bootstrapConfig) {
        this(queueCapacity, workerThreads, bootstrapConfig, MessageResolver.none(), List.of());
    }
    private KuudraApp(int queueCapacity, int workerThreads, KuudraConfig.RuntimeConfig bootstrapConfig,
                      MessageResolver externalMessages, List<HomeInitialization> homeInitializations) {
        this.queueCapacity = queueCapacity;
        this.workerThreads = workerThreads;
        this.bootstrapConfig = bootstrapConfig;
        this.homeInitializations = List.copyOf(homeInitializations);
        this.externalMessageResolver = java.util.Objects.requireNonNull(externalMessages, "externalMessages");
        start();
    }
    public static KuudraApp createDefault() { return new KuudraApp(1_024, Math.max(2, Runtime.getRuntime().availableProcessors() / 2)); }
    public static KuudraApp createConfigured(Path configFile) throws IOException {
        return createConfigured(configFile, MessageResolver.none());
    }
    /** Creates App from a configuration path with external I18n active before startup. */
    public static KuudraApp createConfigured(Path configFile, MessageResolver messages) throws IOException {
        KuudraConfigResource explicit = KuudraYamlLoader.readResource(configFile);
        LoadedConfiguration loaded = loadConfiguration(explicit.baseDirectory(), explicit);
        KuudraConfig.RuntimeConfig config = loaded.config();
        return new KuudraApp(config.runtime().queueCapacity(), config.runtime().workerThreads(), config, messages, loaded.homeInitializations());
    }
    /** Creates App using a programmatic configuration as the highest-priority layer. */
    public static KuudraApp createConfigured(KuudraConfigResource resource) throws IOException {
        return createConfigured(resource, MessageResolver.none());
    }
    /** Creates App with an external I18n layer active before the first startup event is logged. */
    public static KuudraApp createConfigured(KuudraConfigResource resource,
                                             MessageResolver messages) throws IOException {
        LoadedConfiguration loaded = loadConfiguration(resource.baseDirectory(), resource);
        KuudraConfig.RuntimeConfig config = loaded.config();
        return new KuudraApp(config.runtime().queueCapacity(), config.runtime().workerThreads(), config, messages, loaded.homeInitializations());
    }
    /** Creates App from its home config, falling back to the packaged defaults. */
    public static KuudraApp createDefaultOrClasspathConfigured() throws IOException {
        return createFromDefaultLocations();
    }
    /** App-owned configuration precedence: home-directory/config.yaml, then packaged config.yaml. */
    public static KuudraApp createFromDefaultLocations() throws IOException {
        return createFromDefaultLocations(Path.of("."));
    }
    /** Uses the supplied directory as the base for all relative App configuration paths. */
    public static KuudraApp createFromDefaultLocations(Path baseDirectory) throws IOException {
        return createFromDefaultLocations(baseDirectory, MessageResolver.none());
    }
    /** Uses external I18n before startup and packaged English for every unresolved message key. */
    public static KuudraApp createFromDefaultLocations(Path baseDirectory,
                                                       MessageResolver messages) throws IOException {
        Path base = baseDirectory.toAbsolutePath().normalize();
        LoadedConfiguration loaded = loadConfiguration(base, null);
        KuudraConfig.RuntimeConfig config = loaded.config();
        return new KuudraApp(config.runtime().queueCapacity(), config.runtime().workerThreads(), config, messages, loaded.homeInitializations());
    }

    private static LoadedConfiguration loadConfiguration(Path baseDirectory, KuudraConfigResource explicit) throws IOException {
        Path base = baseDirectory.toAbsolutePath().normalize();
        KuudraConfigResource defaults;
        try (var input = KuudraApp.class.getClassLoader().getResourceAsStream("config.yaml")) {
            if (input == null) throw new IOException("Packaged Kuudra configuration is missing: classpath:/config.yaml");
            byte[] defaultConfiguration = input.readAllBytes();
            defaults = KuudraYamlLoader.readResource(new ByteArrayInputStream(defaultConfiguration), base, "classpath:/config.yaml");
            KuudraConfigResource discovery = KuudraYamlLoader.merge(base, "Kuudra configuration discovery", defaults, explicit);
            Object configuredHome = discovery.values().get("home-directory");
            if (!(configuredHome instanceof String home) || home.isBlank()) {
                throw new IOException("Expected non-blank string at home-directory");
            }
            Path homeDirectory = base.resolve(home).normalize();
            List<HomeInitialization> homeInitializations = initializeHomeDirectory(homeDirectory, defaultConfiguration);
            KuudraConfigResource homeConfig = KuudraYamlLoader.readResource(homeDirectory.resolve("config.yaml"));
            KuudraConfigResource merged = KuudraYamlLoader.merge(base, "merged Kuudra configuration", defaults, homeConfig, explicit);
            return new LoadedConfiguration(KuudraYamlLoader.load(merged), homeInitializations);
        }
    }

    private static List<HomeInitialization> initializeHomeDirectory(Path homeDirectory, byte[] defaultConfiguration) throws IOException {
        List<HomeInitialization> created = new ArrayList<>();
        ensureDirectory(homeDirectory, "home", created);
        ensureDirectory(homeDirectory.resolve("plugins"), "plugins", created);
        ensureDirectory(homeDirectory.resolve("manifests"), "manifests", created);
        ensureDirectory(homeDirectory.resolve("logs"), "logs", created);
        ensureDirectory(homeDirectory.resolve("state"), "state", created);
        ensureDirectory(homeDirectory.resolve("locale"), "locale", created);
        Path homeConfigFile = homeDirectory.resolve("config.yaml");
        if (Files.exists(homeConfigFile) && !Files.isRegularFile(homeConfigFile)) {
            throw new IOException("Kuudra home configuration is not a regular file: " + homeConfigFile);
        }
        if (!Files.exists(homeConfigFile)) {
            try {
                Files.write(homeConfigFile, defaultConfiguration, java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException concurrentCreation) {
                if (!Files.isRegularFile(homeConfigFile)) {
                    throw concurrentCreation;
                }
            }
            if (Files.isRegularFile(homeConfigFile)) created.add(new HomeInitialization("configuration", homeConfigFile));
        }
        return List.copyOf(created);
    }

    private static void ensureDirectory(Path directory, String role, List<HomeInitialization> created) throws IOException {
        boolean missing = Files.notExists(directory);
        Files.createDirectories(directory);
        if (missing) created.add(new HomeInitialization(role, directory));
    }

    @Override public synchronized void start() {
        if (status == AppStatus.PAUSED) { resume(); return; }
        if (status == AppStatus.RUNNING || status == AppStatus.STARTING) return;
        try {
            Path home = bootstrapConfig == null ? Path.of(".kuudra") : bootstrapConfig.homeDirectory();
            List<HomeInitialization> startupInitializations = new ArrayList<>();
            if (!homeInitializationReported) startupInitializations.addAll(homeInitializations);
            if (bootstrapConfig != null) {
                try (var defaults = KuudraApp.class.getClassLoader().getResourceAsStream("config.yaml")) {
                    if (defaults == null) throw new IOException("Packaged Kuudra configuration is missing: classpath:/config.yaml");
                    startupInitializations.addAll(initializeHomeDirectory(home, defaults.readAllBytes()));
                }
            }
            configuredMessageResolver = bootstrapConfig == null ? MessageResolvers.english()
                    : MessageResolvers.locale(home.resolve("locale"), bootstrapConfig.i18n().preferredLocale());
            pluginMessageCatalogs = new PluginMessageCatalogs(bootstrapConfig == null
                    ? MessageResolvers.DEFAULT_LOCALE : bootstrapConfig.i18n().preferredLocale());
            KuudraLogConfiguration logging = bootstrapConfig == null
                    ? KuudraLogConfiguration.DEFAULT
                    : new KuudraLogConfiguration(KuudraLogLevel.valueOf(bootstrapConfig.logging().level()),
                    bootstrapConfig.logging().consoleEnabled(), bootstrapConfig.logging().fileEnabled());
            logSession = KuudraLog.openSession(home.resolve("logs"), events, logging, messageResolver);
            status = AppStatus.STARTING; publish("app.starting");
            for (HomeInitialization initialization : startupInitializations) {
                String type = initialization.role().equals("configuration")
                        ? "home.configuration.created" : "home.directory.created";
                events.publish(SystemEvent.of(type, Map.of("role", initialization.role(), "path", initialization.path().toString())));
            }
            homeInitializationReported = true;
            if (bootstrapConfig == null || bootstrapConfig.bannerEnabled()) KuudraBanner.print();
            globalContext = bootstrapConfig == null ? Map.of() : bootstrapConfig.globalContext();
            int maxEventHops = bootstrapConfig == null ? 256 : bootstrapConfig.runtime().maxEventHops();
            int dispatcherPollIntervalMs = bootstrapConfig == null ? 200 : bootstrapConfig.runtime().dispatcherPollIntervalMs();
            int shutdownDrainTimeoutMs = bootstrapConfig == null ? 5_000 : bootstrapConfig.runtime().shutdownSessionDrainTimeoutMs();
            runtime = new KuudraRuntime(queueCapacity, workerThreads, globalContext, maxEventHops, events,
                    dispatcherPollIntervalMs, shutdownDrainTimeoutMs);
            events.publish(SystemEvent.of("runtime.started", Map.of("queueCapacity", queueCapacity, "workerThreads", workerThreads, "maxEventHops", maxEventHops)));
            Path homes = bootstrapConfig == null ? Path.of(".kuudra", "plugins") : bootstrapConfig.homeDirectory().resolve("plugins");
            plugins = new DefaultPluginManager(homes, pluginRuntimeServices(), events);
            plugins.startAll().toCompletableFuture().join();
            if (bootstrapConfig != null) {
                KuudraManifest.Resources currentManifests = KuudraYamlLoader.loadManifests(
                        bootstrapConfig.homeDirectory().resolve("manifests"));
                applyConfiguration(new KuudraConfig.RuntimeConfig(bootstrapConfig.runtime(), bootstrapConfig.resourceSelection(), bootstrapConfig.reconciliation(),
                        bootstrapConfig.stateStore(), bootstrapConfig.logging(), bootstrapConfig.i18n(),
                        bootstrapConfig.homeDirectory(), bootstrapConfig.bannerEnabled(), bootstrapConfig.globalContext(), currentManifests));
            }
            status = AppStatus.RUNNING;
            if (bootstrapConfig != null) startReconciliationLoop(bootstrapConfig.reconciliation());
            detail = ""; publish("app.running");
        } catch (RuntimeException failure) {
            releaseResources();
            status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed"); closeLogSession(); throw failure;
        } catch (IOException failure) {
            status = AppStatus.FAILED; detail = failure.toString(); closeLogSession();
            throw new KuudraException("Failed to initialize Kuudra App configuration", failure);
        }
    }

    @Override public synchronized void stop() {
        if (status == AppStatus.STOPPED || status == AppStatus.CREATED || status == AppStatus.STOPPING) return;
        status = AppStatus.STOPPING; publish("app.stopping"); stopReconciliationLoop();
        try {
            releaseResources();
            status = AppStatus.STOPPED; detail = ""; publish("app.stopped");
            closeLogSession();
        } catch (RuntimeException failure) {
            status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed"); throw failure;
        }
    }

    /** Performs the same graceful stop sequence available to callers, then starts a fresh kernel run. */
    @Override public void restart() {
        stop();
        start();
    }

    @Override public synchronized AppSnapshot snapshot() {
        return runtime == null ? new AppSnapshot(status, 0, 0, detail) : new AppSnapshot(status, runtime.queuedTasks(), runtime.flows().size(), detail);
    }
    /** Current App-owned kernel state, expressed without leaking Runtime implementation types. */
    public synchronized Status status() {
        AppSnapshot snapshot = snapshot();
        List<Flow> flows = runtime == null ? List.of() : runtime.flows().stream().map(KuudraApp::flow).toList();
        int activeSessions = flows.stream().mapToInt(Flow::activeSessions).sum();
        return new Status(snapshot, flows, activeSessions);
    }
    public SystemEventBus systemEvents() { return events; }
    /** Replaces the external I18n layer; unresolved keys continue to fall back to packaged English messages. */
    public void setSystemEventMessageResolver(MessageResolver resolver) {
        externalMessageResolver = java.util.Objects.requireNonNull(resolver, "resolver");
    }
    /** Effective external-first, packaged-English-second resolver used by the active logging session. */
    public MessageResolver systemEventMessageResolver() { return messageResolver; }
    /** Creates a reusable JSON message catalog with {name} placeholders. */
    public static MessageResolver readSystemEventMessages(java.io.InputStream input) throws IOException {
        return MessageResolvers.json(input);
    }
    public Health health() { AppSnapshot snapshot = snapshot(); return new Health(snapshot.status().name(), snapshot.queuedTasks(), snapshot.flowCount()); }
    public synchronized Map<String, Object> globalContext() { return globalContext; }
    public List<Plugin> plugins() { return requirePlugins().pluginViews().stream().map(KuudraApp::plugin).toList(); }
    public Optional<Plugin> plugin(String namespace, String pluginId) {
        try { return Optional.of(plugin(requirePlugins().pluginView(namespace, pluginId))); }
        catch (IllegalArgumentException missing) { return Optional.empty(); }
    }

    /** Globally suspends event admission and waits queued work at Runtime safe points. */
    public void pause() {
        final KuudraRuntime target;
        synchronized (this) {
            if (status == AppStatus.PAUSED || status == AppStatus.PAUSING) return;
            if (status != AppStatus.RUNNING || runtime == null) throw new KuudraException("App is not running: " + status);
            target = runtime;
            status = AppStatus.PAUSING; detail = "waiting for Runtime safe point"; publish("app.pausing");
        }
        try {
            RuntimeCheckpoint runtimeCheckpoint = target.pause();
            synchronized (this) {
                if (status != AppStatus.PAUSING || runtime != target) return;
                checkpoint = new KernelCheckpoint(runtimeCheckpoint,
                        manifestResources.components().values().stream().map(this::componentResource).toList());
                status = AppStatus.PAUSED; detail = "paused"; publish("app.paused");
            }
        } catch (RuntimeException failure) {
            synchronized (this) {
                if (status == AppStatus.STOPPING || status == AppStatus.STOPPED || runtime != target) return;
                status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed");
            }
            throw failure;
        }
    }

    /** Resumes a globally paused kernel without rebuilding plugins, resources, or Sessions. */
    public synchronized void resume() {
        if (status == AppStatus.RUNNING) return;
        if (status != AppStatus.PAUSED || runtime == null) throw new KuudraException("App is not paused: " + status);
        status = AppStatus.RESUMING; detail = "resuming"; publish("app.resuming");
        try {
            runtime.resume(); checkpoint = null; status = AppStatus.RUNNING; detail = ""; publish("app.resumed");
        } catch (RuntimeException failure) {
            status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed"); throw failure;
        }
    }
    public synchronized Optional<KernelCheckpoint> checkpoint() { return Optional.ofNullable(checkpoint); }
    public List<Component> components() { return requirePlugins().componentViews().stream().map(KuudraApp::component).toList(); }
    public List<Component> pluginComponents(String namespace, String pluginId) {
        return plugin(namespace, pluginId).orElseThrow(() -> new IllegalArgumentException(
                "Unknown plugin: " + namespace + "/" + pluginId)).components();
    }
    public Optional<Component> pluginComponent(String reference) {
        try { return Optional.of(component(requirePlugins().componentView(reference))); }
        catch (IllegalArgumentException missing) { return Optional.empty(); }
    }
    public Optional<ComponentDocumentation> pluginComponentDocumentation(String reference) {
        return pluginComponent(reference).map(Component::documentation);
    }
    public Optional<ComponentDocumentation> pluginComponentDocumentation(String namespace, String pluginId,
                                                                         String type, String name) {
        return plugin(namespace, pluginId).flatMap(owner -> owner.components().stream()
                .filter(component -> component.reference().equals(type + "/" + namespace + "/" + name))
                .map(Component::documentation).findFirst());
    }

    public synchronized List<Flow> flows() {
        Map<String, FlowSnapshot> running = runtime == null ? Map.of() : runtime.flows().stream()
                .collect(java.util.stream.Collectors.toMap(FlowSnapshot::flowId, snapshot -> snapshot));
        return desiredResources().flows().values().stream().map(declaration -> {
            FlowSnapshot snapshot = running.get(declaration.id().qualifiedName());
            return snapshot == null
                    ? new Flow(declaration.id().qualifiedName(), 0, 0, selected(declaration.metadata().namespace()))
                    : new Flow(snapshot.flowId(), snapshot.activeSessions(), snapshot.deferredTasks(), true);
        }).toList();
    }
    public List<Flow> flows(String namespace) { return flows().stream().filter(flow -> flow.id().startsWith(namespace + "/")).toList(); }
    public synchronized Optional<Flow> flow(String flowId) {
        return flows().stream().filter(flow -> flow.id().equals(flowId)).findFirst();
    }
    public Optional<Flow> flow(String namespace, String name) { return flow(namespace + "/" + name); }
    /** Core resource schemas use a documentation-provider namespace, independent from resource instance namespaces. */
    public List<ResourceDocumentation> resourceDocumentations() { return List.of(FLOW_DOCUMENTATION); }
    public Optional<ResourceDocumentation> resourceDocumentation(String namespace, String kind) {
        return resourceDocumentations().stream().filter(documentation -> documentation.namespace().equals(namespace)
                && documentation.kind().equalsIgnoreCase(kind)).findFirst();
    }
    /** Event sources are independently controllable resources; a Flow groups them but does not start/stop them implicitly. */
    public synchronized List<Resource> eventSources() { return eventSources.values().stream().map(ManagedEventSource::snapshot).toList(); }
    public synchronized List<Resource> eventSources(String flowId) {
        requireRuntime().flow(flowId).orElseThrow(() -> new IllegalArgumentException("Unknown Flow: " + flowId));
        return eventSources.values().stream().filter(source -> source.key.flowId.equals(flowId)).map(ManagedEventSource::snapshot).toList();
    }
    public synchronized Resource eventSource(String flowId, String resourceId) { return requireEventSource(flowId, resourceId).snapshot(); }
    /** Programmatic counterpart of a compose-style event-source component declaration. */
    public synchronized Resource declareEventSource(String flowId, String resourceId, EventSource source, String targetNodeId) {
        requireRuntime().flow(flowId).orElseThrow(() -> new IllegalArgumentException("Unknown Flow: " + flowId));
        ResourceKey key = new ResourceKey(flowId, resourceId);
        if (eventSources.putIfAbsent(key, new ManagedEventSource(key, null, targetNodeId, source)) != null) throw new IllegalArgumentException("Duplicate EventSource resource: " + flowId + "/" + resourceId);
        return requireEventSource(flowId, resourceId).snapshot();
    }
    public synchronized Resource startEventSource(String flowId, String resourceId) {
        ManagedEventSource managed = requireEventSource(flowId, resourceId);
        if (managed.registration == null) {
            if (managed.source == null) managed.source = requirePlugins().createComponent(componentReference("event-source", managed.componentReference), EventSource.class);
            managed.registration = requireRuntime().registerSource(flowId, managed.targetNodeId, managed.source).toCompletableFuture().join();
            events.publish(SystemEvent.of("resource.event-source.started", Map.of("flowId", flowId, "resourceId", resourceId, "target", managed.targetNodeId)));
        }
        return managed.snapshot();
    }
    public synchronized Resource stopEventSource(String flowId, String resourceId) {
        ManagedEventSource managed = requireEventSource(flowId, resourceId);
        if (managed.registration != null) {
            managed.registration.unregister().toCompletableFuture().join(); managed.registration = null;
            events.publish(SystemEvent.of("resource.event-source.stopped", Map.of("flowId", flowId, "resourceId", resourceId)));
        }
        return managed.snapshot();
    }
    /** Returns every manifest-declared component resource, including passive pipeline components. */
    public synchronized List<ComponentResource> componentResources() {
        return desiredResources().components().values().stream().map(this::componentResource).toList();
    }
    public synchronized List<ResourceStateStore.ResourceState> resourceStates() {
        return stateStore == null ? List.of() : stateStore.states();
    }
    public synchronized List<ComponentResource> componentResources(String type) {
        return desiredResources().components().values().stream().filter(component -> component.type().equals(type))
                .map(this::componentResource).toList();
    }
    public synchronized List<ComponentResource> resourcesInNamespace(String namespace) {
        return desiredResources().components().values().stream()
                .filter(component -> component.metadata().namespace().equals(namespace)).map(this::componentResource).toList();
    }
    public synchronized Optional<ComponentResource> resource(String kind, String namespace, String name) {
        KuudraManifest.Component component = desiredResources().components().get(new KuudraManifest.ResourceId(kind, namespace, name));
        return component == null ? Optional.empty() : Optional.of(componentResource(component));
    }
    /** Updates persisted desired state first, then lets the App-owned reconciler converge one component. */
    public synchronized ComponentResource setDesiredState(String kind, String namespace, String name, String desiredState) {
        requireRuntime();
        if (!selected(namespace)) throw new KuudraException("Resource namespace is excluded from this deployment: " + namespace);
        KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(kind, namespace, name);
        KuudraManifest.Resources currentDesired = desiredResources();
        KuudraManifest.Component current = currentDesired.components().get(id);
        if (current == null) throw new IllegalArgumentException("Unknown Component resource: " + id);
        KuudraManifest.Component updated = new KuudraManifest.Component(id, current.metadata(),
                current.component(), desiredState.toLowerCase(java.util.Locale.ROOT), current.options());
        Map<KuudraManifest.ResourceId,KuudraManifest.Component> components = new LinkedHashMap<>(currentDesired.components());
        components.put(id, updated);
        KuudraManifest.Resources desired = new KuudraManifest.Resources(components, currentDesired.flows());
        validateDesiredStates(desired);
        debug("resource.reconcile.started", Map.of("resource", id.toString(), "from", current.desiredState(), "to", updated.desiredState()));
        stateStore.replaceDesired(desired);
        manifestResources = selectResources(desired);
        try {
            reconcileComponent(updated);
            stateStore.markObserved(id, "READY", "reconciled");
            debug("resource.reconcile.completed", Map.of("resource", id.toString(), "desiredState", updated.desiredState(), "outcome", "ready"));
            return componentResource(updated);
        } catch (RuntimeException failure) {
            stateStore.markFailed(id, failure.toString()); throw failure;
        }
    }
    public synchronized Optional<ComponentResource> componentResource(String type, String namespace, String name) {
        String kind = KuudraManifest.COMPONENT_KINDS.entrySet().stream().filter(entry -> entry.getValue().equals(type))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (kind == null) return Optional.empty();
        KuudraManifest.Component component = desiredResources().components().get(new KuudraManifest.ResourceId(kind, namespace, name));
        return component == null || !component.type().equals(type) ? Optional.empty() : Optional.of(componentResource(component));
    }
    public Optional<Session> session(UUID sessionId) { return requireRuntime().session(sessionId).map(KuudraApp::session); }
    public boolean cancelSession(UUID sessionId) { return requireRuntime().cancel(sessionId); }
    public boolean pauseSession(UUID sessionId) { return requireRuntime().pauseSession(sessionId); }
    public boolean resumeSession(UUID sessionId) { return requireRuntime().resumeSession(sessionId); }
    public void registerFlow(KuudraFlow flow) { requireRuntime().registerFlow(flow); }
    public boolean publish(String flowId, String targetNodeId, KuudraEvent event) { return requireRuntime().publish(flowId, targetNodeId, event); }
    public boolean awaitNoActiveSessions(Duration timeout) throws InterruptedException { return requireRuntime().awaitNoActiveSessions(timeout); }

    public synchronized void loadPlugin(Path archive) throws IOException {
        events.publish(SystemEvent.of("plugin.archive.loading", Map.of("archive", archive.toAbsolutePath().normalize().toString())));
        PluginArchiveLoader.LoadedArchive loaded = new PluginArchiveLoader().loadAll(List.of(archive),
                KuudraApp.class.getClassLoader()).get(0);
        try {
            registerPluginMessages(loaded);
            requirePlugins().register(loaded.plugin()); archives.add(loaded);
            events.publish(SystemEvent.of("plugin.archive.loaded", Map.of("archive", archive.toAbsolutePath().normalize().toString(), "pluginId", loaded.plugin().metadata().id())));
        }
        catch (IOException | RuntimeException error) { try { loaded.close(); } catch (IOException closeError) { error.addSuppressed(closeError); } throw error; }
    }
    public java.util.concurrent.CompletionStage<Void> startPlugins() { return requirePlugins().startAll(); }
    public PluginComponentRegistry pluginComponents() { return requirePlugins().components(); }
    public java.util.concurrent.CompletionStage<SourceRegistration> installEventSource(String componentReference, String flowId, String targetNodeId) {
        EventSource source = requirePlugins().createComponent(componentReference, EventSource.class);
        return requireRuntime().registerSource(flowId, targetNodeId, source);
    }

    private KuudraRuntime requireRuntime() { synchronized (this) { if ((status != AppStatus.STARTING && status != AppStatus.RUNNING && status != AppStatus.PAUSED) || runtime == null) throw new KuudraException("App is not available: " + status); return runtime; } }
    private DefaultPluginManager requirePlugins() { synchronized (this) { if ((status != AppStatus.STARTING && status != AppStatus.RUNNING && status != AppStatus.PAUSED) || plugins == null) throw new KuudraException("App is not available: " + status); return plugins; } }
    private void publish(String type) { events.publish(SystemEvent.of(type, java.util.Map.of("status", status.name(), "detail", detail))); }
    private void debug(String type, Map<String,Object> data) { events.publish(SystemEvent.debug(type, data)); }
    private void trace(String type, Map<String,Object> data) { events.publish(SystemEvent.trace(type, data)); }
    @Override public synchronized void close() {
        stop();
        if (stateStore != null) try { stateStore.close(); } finally { stateStore = null; }
    }
    private static Flow flow(FlowSnapshot snapshot) { return new Flow(snapshot.flowId(), snapshot.activeSessions(), snapshot.deferredTasks(), true); }
    private static Session session(SessionSnapshot snapshot) { return new Session(snapshot.id(), snapshot.flowId(), snapshot.flowRevision(), snapshot.ingressId(), snapshot.groupKey(), snapshot.status().name(), snapshot.cancellationRequested(), snapshot.activeLeases()); }
    private static Plugin plugin(DefaultPluginManager.PluginView view) {
        return new Plugin(view.id(), view.namespace(), view.version(), view.state().name(), view.dependencies().stream()
                .map(dependency -> new Dependency(dependency.namespace(), dependency.pluginId(), dependency.mandatory(), dependency.versionRange())).toList(),
                view.components().stream().map(KuudraApp::component).toList());
    }
    private static Component component(DefaultPluginManager.ComponentView view) {
        var documentation = view.documentation();
        return new Component(view.reference(), view.pluginId(), view.namespace(), view.kind().manifestKind(), view.name(),
                view.implementation(), new InstancePolicy(view.instancePolicy().maxInstances(),
                view.instancePolicy().limitScope().name(), view.instancePolicy().exclusivityDomain(),
                view.instancePolicy().shareable(), view.instancePolicy().threadSafe()),
                new ComponentDocumentation(documentation.purpose(), documentation.lifecycle(),
                        documentation.lifecyclePhases(), documentation.supportedDesiredStates(), documentation.configuration().stream()
                        .map(property -> new ConfigurationDocumentation(property.path(), property.type(), property.required(),
                                property.defaultValue(), property.description(), property.examples(), property.allowedValues())).toList(),
                        documentation.emittedEvents().stream()
                        .map(event -> new EventDocumentation(event.stage(), event.eventType(), event.description(), event.dataExample())).toList()));
    }
    public record Health(String status, int queuedTasks, int flows) { }
    public record Status(AppSnapshot app, List<Flow> flows, int activeSessions) {
        public Status { flows = List.copyOf(flows); }
    }
    public synchronized void loadPluginArchives(List<Path> pluginArchives) throws IOException {
        List<PluginArchiveLoader.LoadedArchive> loaded = new PluginArchiveLoader().loadAll(pluginArchives,
                KuudraApp.class.getClassLoader());
        try {
            for (PluginArchiveLoader.LoadedArchive archive : loaded) {
                registerPluginMessages(archive);
                requirePlugins().register(archive.plugin());
            }
            archives.addAll(loaded);
        } catch (RuntimeException error) {
            for (PluginArchiveLoader.LoadedArchive archive : loaded) try { archive.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    /** Loads plugin archives, resolves their metadata dependencies, and assembles every configured Event Flow. */
    private void applyConfiguration(KuudraConfig.RuntimeConfig config) {
        try {
            debug("configuration.apply.started", Map.of("homeDirectory", config.homeDirectory().toString(),
                    "components", config.manifests().components().size(), "flows", config.manifests().flows().size()));
            Path pluginDirectory = config.homeDirectory().resolve("plugins");
            Files.createDirectories(pluginDirectory);
            debug("plugin.scan.started", Map.of("directory", pluginDirectory.toString()));
            List<Path> pluginArchives;
            try (var files = Files.list(pluginDirectory)) {
                pluginArchives = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        .sorted().toList();
            }
            debug("plugin.scan.completed", Map.of("directory", pluginDirectory.toString(), "archives", pluginArchives.size()));
            loadPluginArchives(pluginArchives);
            startPlugins().toCompletableFuture().join();
            if (stateStore == null) stateStore = new SqliteResourceStateStore(
                    config.homeDirectory().resolve("state").resolve("kuudra.db"), config.stateStore().busyTimeoutMs());
            validateDesiredStates(config.manifests());
            stateStore.replaceDesired(config.manifests());
            resourceSelection = config.resourceSelection();
            debug("state.desired.replaced", Map.of("components", config.manifests().components().size(),
                    "flows", config.manifests().flows().size()));
            KuudraManifest.Resources selected = selectResources(stateStore.desiredResources());
            validateManifestPolicies(selected);
            stateStore.markAllObserved("EXCLUDED", "namespace excluded from this deployment");
            applyManifests(selected);
            markSelectedObserved(selected);
            debug("configuration.apply.completed", Map.of("components", manifestResources.components().size(),
                    "flows", manifestResources.flows().size()));
        } catch (IOException | RuntimeException error) {
            throw KuudraException.wrap("Failed to apply Kuudra configuration", error);
        }
    }

    private void releaseResources() {
        stopReconciliationLoop();
        if (runtime != null) {
            debug("app.shutdown.runtime.started", Map.of());
            try { runtime.close(); }
            catch (RuntimeException error) { events.publish(SystemEvent.of("app.shutdown.runtime.failed", Map.of("error", error.toString()))); }
            debug("app.shutdown.runtime.completed", Map.of());
        }
        if (plugins != null) {
            debug("app.shutdown.plugins.started", Map.of());
            try { plugins.close(); }
            catch (RuntimeException error) { events.publish(SystemEvent.of("app.shutdown.plugins.failed", Map.of("error", error.toString()))); }
            debug("app.shutdown.plugins.completed", Map.of());
        }
        debug("app.shutdown.archives.started", Map.of("archives", archives.size()));
        for (PluginArchiveLoader.LoadedArchive archive : archives) try { archive.close(); }
        catch (IOException error) { events.publish(SystemEvent.of("app.shutdown.archive.failed", Map.of(
                "archive", archive.archive().toString(), "error", error.toString()))); }
        debug("app.shutdown.archives.completed", Map.of("archives", archives.size()));
        archives.clear();
        eventSources.clear();
        manifestInstances.clear();
        manifestSourceRegistrations.clear();
        manifestSourceTargets.clear();
        manifestObservedStates.clear();
        manifestResources = KuudraManifest.Resources.EMPTY;
        checkpoint = null;
        runtime = null;
        plugins = null;
        globalContext = Map.of();
    }

    private void registerPluginMessages(PluginArchiveLoader.LoadedArchive loaded) throws IOException {
        var metadata = loaded.plugin().metadata();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(loaded.archive().toFile())) {
            java.util.Set<String> locales = new java.util.LinkedHashSet<>();
            locales.add(pluginMessageCatalogs.preferredLocale()); locales.add(MessageResolvers.DEFAULT_LOCALE);
            for (String locale : locales) {
                String path = "META-INF/kuudra-plugin/i18n/" + locale + ".json";
                java.util.jar.JarEntry entry = jar.getJarEntry(path);
                if (entry != null) try (var input = jar.getInputStream(entry)) {
                    pluginMessageCatalogs.register(metadata.namespace(), metadata.id(), locale, input);
                }
            }
        }
    }

    private void startReconciliationLoop(KuudraConfig.ReconciliationSettings settings) {
        if (!settings.enabled() || reconciliationExecutor != null) return;
        reconciliationExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kuudra-app-reconciler");
            thread.setDaemon(true);
            return thread;
        });
        reconciliationExecutor.scheduleWithFixedDelay(this::reconcilePendingSafely,
                settings.intervalMs(), settings.intervalMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        debug("reconciliation.loop.started", Map.of("intervalMs", settings.intervalMs()));
    }

    private synchronized void reconcilePendingSafely() {
        if (status != AppStatus.RUNNING || stateStore == null || runtime == null) return;
        long startedAt = System.nanoTime();
        int attempted = 0;
        int failed = 0;
        trace("reconciliation.cycle.started", Map.of());
        try {
            KuudraManifest.Resources desired = stateStore.desiredResources();
            KuudraManifest.Resources selectedResources = selectResources(desired);
            manifestResources = selectedResources;
            Map<KuudraManifest.ResourceId, ResourceStateStore.ResourceState> states = stateStore.states().stream()
                    .collect(java.util.stream.Collectors.toMap(ResourceStateStore.ResourceState::id, state -> state));
            for (KuudraManifest.Component component : selectedResources.components().values()) {
                ResourceStateStore.ResourceState state = states.get(component.id());
                if (state == null || (state.generation() == state.observedGeneration() && !"FAILED".equals(state.phase()))) continue;
                attempted++;
                try {
                    debug("resource.reconcile.retry", Map.of("resource", component.id().toString(), "generation", state.generation()));
                    reconcileComponent(component);
                    stateStore.markObserved(component.id(), "READY", "reconciled by periodic loop");
                } catch (RuntimeException failure) {
                    failed++;
                    stateStore.markFailed(component.id(), failure.toString());
                }
            }
        } catch (RuntimeException failure) {
            events.publish(SystemEvent.of("reconciliation.loop.failed", Map.of("error", failure.toString())));
            failed++;
        } finally {
            trace("reconciliation.cycle.completed", Map.of("attemptedComponents", attempted,
                    "failedComponents", failed,
                    "elapsedMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
        }
    }

    private void stopReconciliationLoop() {
        if (reconciliationExecutor == null) return;
        reconciliationExecutor.shutdownNow();
        reconciliationExecutor = null;
    }

    private void closeLogSession() {
        if (logSession == null) return;
        debug("app.shutdown.logging.started", Map.of("operation", "flush-and-archive"));
        try { logSession.close(); } finally { logSession = null; }
    }

    private KuudraManifest.Resources selectResources(KuudraManifest.Resources resources) {
        Map<KuudraManifest.ResourceId, KuudraManifest.Component> components = resources.components().entrySet().stream()
                .filter(entry -> selected(entry.getKey().namespace()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
        Map<KuudraManifest.ResourceId, KuudraManifest.Flow> flows = resources.flows().entrySet().stream()
                .filter(entry -> selected(entry.getKey().namespace()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
        return new KuudraManifest.Resources(components, flows);
    }

    private void markSelectedObserved(KuudraManifest.Resources selectedResources) {
        selectedResources.components().keySet().forEach(id -> stateStore.markObserved(id, "READY", "reconciled"));
        selectedResources.flows().keySet().forEach(id -> stateStore.markObserved(id, "READY", "reconciled"));
    }

    private boolean selected(String namespace) { return resourceSelection.selects(namespace); }


    private void applyManifests(KuudraManifest.Resources resources) {
        if (resources.isEmpty()) return;
        validateDesiredStates(resources);
        validateManifestPolicies(resources);
        debug("manifest.validation.completed", Map.of("components", resources.components().size(), "flows", resources.flows().size()));
        for (KuudraManifest.Component component : resources.components().values()) {
            debug("component.materializing", Map.of("resource", component.id().toString(), "component", component.component()));
            Object instance = switch (component.type()) {
                case "event-source" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventSource.class, component.options());
                case "event-adapter" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventAdapter.class, component.options());
                case "event-interpreter" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventInterpreter.class, component.options());
                case "event-handler" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventHandler.class, component.options());
                case "ingress" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), Ingress.class, component.options());
                case "egress" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), Egress.class, component.options());
                default -> throw new IllegalArgumentException("Unsupported Component type: " + component.type());
            };
            manifestInstances.put(component.id(), instance);
            manifestObservedStates.put(component.id(), instance instanceof Lifecycle ? "STOPPED" : "ACTIVE");
            debug("component.materialized", Map.of("resource", component.id().toString(), "instanceClass", instance.getClass().getName()));
        }
        Map<KuudraManifest.ResourceId, List<KuudraRuntime.SourceTarget>> sourceTargets = new LinkedHashMap<>();
        for (KuudraManifest.Flow flow : resources.flows().values()) {
            debug("flow.compiling", Map.of("flow", flow.id().qualifiedName(), "imports", flow.imports().size(), "edges", flow.edges().size()));
            registerFlow(compile(flow, resources.components(), sourceTargets));
        }
        for (KuudraManifest.Component component : resources.components().values()) {
            if (!component.type().equals("event-source") && manifestInstances.get(component.id()) instanceof Lifecycle) {
                recordComponentStateChange(component, "RUNNING");
                reconcileLifecycle(component, (Lifecycle) manifestInstances.get(component.id()));
            } else if (!component.type().equals("event-source")) {
                boolean active = component.desiredState().equalsIgnoreCase("active");
                requireRuntime().setComponentEnabled(manifestInstances.get(component.id()), active);
                manifestObservedStates.put(component.id(), active ? "ACTIVE" : "INACTIVE");
            }
        }
        for (KuudraManifest.Component component : resources.components().values()) {
            if (!component.type().equals("event-source")) continue;
            String desired = component.desiredState().toLowerCase(java.util.Locale.ROOT);
            if (desired.equals("stopped")) continue;
            List<KuudraRuntime.SourceTarget> targets = sourceTargets.getOrDefault(component.id(), List.of());
            EventSource source = (EventSource) manifestInstances.get(component.id());
            manifestSourceRegistrations.put(component.id(), requireRuntime().registerSource(targets, source).toCompletableFuture().join());
            requireRuntime().setComponentEnabled(source, true);
            manifestObservedStates.put(component.id(), "RUNNING");
            if (desired.equals("paused")) {
                if (!(source instanceof PausableLifecycle pausable)) throw new KuudraException("EventSource is not pausable: " + component.id());
                requireRuntime().setComponentPaused(source, true);
                pausable.pause().toCompletableFuture().join();
                manifestObservedStates.put(component.id(), "PAUSED");
            }
        }
        manifestSourceTargets.putAll(sourceTargets);
        manifestResources = resources;
    }

    private void reconcileComponent(KuudraManifest.Component component) {
        boolean source = component.type().equals("event-source");
        String desired = component.desiredState().toLowerCase(java.util.Locale.ROOT);
        if (source) {
            if ((desired.equals("running") || desired.equals("paused")) && !manifestSourceRegistrations.containsKey(component.id())) {
                EventSource instance = (EventSource) manifestInstances.computeIfAbsent(component.id(), ignored -> createManifestComponent(component));
                List<KuudraRuntime.SourceTarget> targets = manifestSourceTargets.getOrDefault(component.id(), List.of());
                manifestSourceRegistrations.put(component.id(), requireRuntime().registerSource(targets, instance).toCompletableFuture().join());
                requireRuntime().setComponentEnabled(instance, true);
                recordComponentStateChange(component, "RUNNING");
                if (desired.equals("paused")) {
                    requireRuntime().setComponentPaused(instance, true);
                    ((PausableLifecycle) instance).pause().toCompletableFuture().join();
                    recordComponentStateChange(component, "PAUSED");
                }
            } else if (desired.equals("running") && manifestObservedStates.getOrDefault(component.id(), "").equals("PAUSED")) {
                ((PausableLifecycle) manifestInstances.get(component.id())).resume().toCompletableFuture().join();
                requireRuntime().setComponentPaused(manifestInstances.get(component.id()), false);
                recordComponentStateChange(component, "RUNNING");
            } else if (desired.equals("paused") && manifestObservedStates.getOrDefault(component.id(), "").equals("RUNNING")) {
                requireRuntime().setComponentPaused(manifestInstances.get(component.id()), true);
                ((PausableLifecycle) manifestInstances.get(component.id())).pause().toCompletableFuture().join();
                recordComponentStateChange(component, "PAUSED");
            } else if (desired.equals("stopped")) {
                SourceRegistration registration = manifestSourceRegistrations.remove(component.id());
                if (registration != null) registration.unregister().toCompletableFuture().join();
                recordComponentStateChange(component, "STOPPED");
            }
            return;
        }
        Object currentInstance = manifestInstances.get(component.id());
        if (currentInstance instanceof Lifecycle lifecycle) {
            reconcileLifecycle(component, lifecycle);
            return;
        }
        if (desired.equals("inactive")) {
            requireRuntime().setComponentEnabled(currentInstance, false);
            recordComponentStateChange(component, "INACTIVE");
        } else {
            if (currentInstance == null) {
                currentInstance = createManifestComponent(component);
                manifestInstances.put(component.id(), currentInstance);
            }
            requireRuntime().setComponentEnabled(currentInstance, true);
            recordComponentStateChange(component, "ACTIVE");
        }
    }

    private void reconcileLifecycle(KuudraManifest.Component component, Lifecycle lifecycle) {
        String desired = component.desiredState().toUpperCase(java.util.Locale.ROOT);
        String observed = manifestObservedStates.getOrDefault(component.id(), "STOPPED");
        if (desired.equals(observed)) {
            if (desired.equals("PAUSED")) requireRuntime().setComponentPaused(lifecycle, true);
            else requireRuntime().setComponentEnabled(lifecycle, desired.equals("RUNNING"));
            return;
        }
        switch (desired) {
            case "RUNNING" -> {
                if (observed.equals("PAUSED")) ((PausableLifecycle) lifecycle).resume().toCompletableFuture().join();
                else lifecycle.start().toCompletableFuture().join();
                requireRuntime().setComponentPaused(lifecycle, false);
            }
            case "PAUSED" -> {
                if (observed.equals("STOPPED")) lifecycle.start().toCompletableFuture().join();
                requireRuntime().setComponentPaused(lifecycle, true);
                ((PausableLifecycle) lifecycle).pause().toCompletableFuture().join();
            }
            case "STOPPED" -> {
                requireRuntime().setComponentEnabled(lifecycle, false);
                lifecycle.stop().toCompletableFuture().join();
            }
            default -> throw new KuudraException("Unsupported lifecycle desiredState " + desired + " for " + component.id());
        }
        recordComponentStateChange(component, desired);
    }

    private void recordComponentStateChange(KuudraManifest.Component component, String state) {
        String previous = manifestObservedStates.put(component.id(), state);
        if (!java.util.Objects.equals(previous, state)) {
            Map<String, Object> data = Map.of("resource", component.id().toString(),
                    "from", previous == null ? "UNOBSERVED" : previous, "to", state,
                    "desiredState", component.desiredState());
            debug("component.state.changed", data);
            events.publish(SystemEvent.of("resource.state.changed", data));
        }
    }

    private Object createManifestComponent(KuudraManifest.Component component) {
        return switch (component.type()) {
            case "event-source" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventSource.class, component.options());
            case "event-adapter" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventAdapter.class, component.options());
            case "event-interpreter" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventInterpreter.class, component.options());
            case "event-handler" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventHandler.class, component.options());
            case "ingress" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), Ingress.class, component.options());
            case "egress" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), Egress.class, component.options());
            default -> throw new IllegalArgumentException("Unsupported Component type: " + component.type());
        };
    }

    private PluginRuntimeServices pluginRuntimeServices() {
        return new PluginRuntimeServices() {
            @Override public java.util.concurrent.CompletionStage<SourceRegistration> registerEventSource(
                    String flowId, String targetNodeId, EventSource source) {
                return requireRuntime().registerSource(flowId, targetNodeId, source);
            }
            @Override public java.util.concurrent.CompletionStage<Void> control(KernelControlAction action, UUID sessionId) {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    boolean changed = switch (action) {
                        case PAUSE_KERNEL -> { pause(); yield true; }
                        case RESUME_KERNEL -> { resume(); yield true; }
                        case STOP_KERNEL -> { stop(); yield true; }
                        case PAUSE_SESSION -> requireRuntime().pauseSession(requireSessionId(action, sessionId));
                        case RESUME_SESSION -> requireRuntime().resumeSession(requireSessionId(action, sessionId));
                        case CANCEL_SESSION -> requireRuntime().cancel(requireSessionId(action, sessionId));
                    };
                    if (!changed) throw new KuudraException("Kernel control request was not applicable: " + action);
                }).exceptionally(failure -> {
                    events.publish(SystemEvent.of("kernel.control.failed", Map.of(
                            "action", action.name(), "error", failure.toString())));
                    return null;
                });
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
    }

    private static UUID requireSessionId(KernelControlAction action, UUID sessionId) {
        if (sessionId == null) throw new KuudraException(action + " requires a Session context");
        return sessionId;
    }

    private void validateDesiredStates(KuudraManifest.Resources resources) {
        for (KuudraManifest.Component component : resources.components().values()) {
            PluginComponentDefinition definition = requirePlugins().components().find(componentReference(component.type(), component.component()))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown component: " + component.component()));
            java.util.Set<String> supported = new java.util.LinkedHashSet<>(definition.documentation().supportedDesiredStates());
            if (!supported.contains(component.desiredState().toUpperCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Unsupported " + component.id().kind() + " desiredState: "
                        + component.desiredState() + "; expected " + supported);
            }
        }
    }

    private KuudraFlow compile(KuudraManifest.Flow definition,
                               Map<KuudraManifest.ResourceId, KuudraManifest.Component> components,
                               Map<KuudraManifest.ResourceId, List<KuudraRuntime.SourceTarget>> sourceTargets) {
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        Map<String, EventDomain> adapterDomains = inferAdapterDomains(definition, components);
        for (Map.Entry<String, KuudraManifest.ResourceReference> imported : definition.imports().entrySet()) {
            KuudraManifest.Component component = components.get(imported.getValue().id());
            if (component == null) throw new IllegalArgumentException("Unknown imported Component: " + imported.getValue().id());
            Object instance = manifestInstances.get(component.id());
            FlowNode node = switch (component.type()) {
                case "event-source" -> null;
                case "event-adapter" -> new FlowNode.AdapterNode(imported.getKey(), (EventAdapter) instance, adapterDomains.get(imported.getKey()), component.options());
                case "event-interpreter" -> new FlowNode.InterpreterNode(imported.getKey(), (EventInterpreter) instance, component.options());
                case "event-handler" -> new FlowNode.HandlerNode(imported.getKey(), (EventHandler) instance, component.options());
                case "ingress" -> new FlowNode.IngressNode(imported.getKey(), component.id().qualifiedName(), (Ingress) instance, ingressConfiguration(component.options()), component.options());
                case "egress" -> new FlowNode.EgressNode(imported.getKey(), (Egress) instance, component.options());
                default -> throw new IllegalArgumentException("Unsupported Component type: " + component.type());
            };
            if (node != null) nodes.put(imported.getKey(), node);
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (KuudraConfig.EdgeConfig edge : definition.edges()) {
            KuudraManifest.Component from = components.get(definition.imports().get(edge.from()).id());
            KuudraManifest.Component to = components.get(definition.imports().get(edge.to()).id());
            if (to.type().equals("event-source")) throw new IllegalArgumentException("Flow edge cannot target EventSource: " + edge.to());
            if (from.type().equals("event-source")) {
                sourceTargets.computeIfAbsent(from.id(), ignored -> new ArrayList<>())
                        .add(new KuudraRuntime.SourceTarget(definition.id().qualifiedName(), edge.to()));
            } else edges.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
        }
        return new KuudraFlow(definition.id().qualifiedName(), nodes, edges);
    }

    static Map<String, EventDomain> inferAdapterDomains(KuudraManifest.Flow flow,
                                                         Map<KuudraManifest.ResourceId, KuudraManifest.Component> components) {
        Map<String, EventDomain> domains = new LinkedHashMap<>();
        boolean changed;
        do {
            changed = false;
            for (KuudraConfig.EdgeConfig edge : flow.edges()) {
                KuudraManifest.Component from = components.get(flow.imports().get(edge.from()).id());
                KuudraManifest.Component to = components.get(flow.imports().get(edge.to()).id());
                EventDomain output = from.type().equals("event-adapter") ? domains.get(edge.from()) : fixedOutputDomain(from.type());
                EventDomain input = to.type().equals("event-adapter") ? domains.get(edge.to()) : fixedInputDomain(to.type());
                if (from.type().equals("event-adapter") && output == null && input != null) {
                    domains.put(edge.from(), input); output = input; changed = true;
                }
                if (to.type().equals("event-adapter") && input == null && output != null) {
                    domains.put(edge.to(), output); input = output; changed = true;
                }
                if (output != null && input != null && output != input) {
                    throw new KuudraException("Flow domain mismatch: " + edge.from() + "(" + output + ") -> " + edge.to() + "(" + input + ")");
                }
            }
        } while (changed);
        for (Map.Entry<String, KuudraManifest.ResourceReference> imported : flow.imports().entrySet()) {
            KuudraManifest.Component component = components.get(imported.getValue().id());
            if (component.type().equals("event-adapter") && !domains.containsKey(imported.getKey())) {
                throw new KuudraException("Cannot infer EventAdapter domain for Flow import '" + imported.getKey()
                        + "'; connect it to a domain-defining component");
            }
        }
        return Map.copyOf(domains);
    }

    private static EventDomain fixedInputDomain(String type) {
        return switch (type) {
            case "event-interpreter", "ingress" -> EventDomain.RAW;
            case "event-handler", "egress" -> EventDomain.SESSION;
            default -> null;
        };
    }

    private static EventDomain fixedOutputDomain(String type) {
        return switch (type) {
            case "event-source", "event-interpreter", "egress" -> EventDomain.RAW;
            case "ingress", "event-handler" -> EventDomain.SESSION;
            default -> null;
        };
    }

    private void validateManifestPolicies(KuudraManifest.Resources resources) {
        Map<String, Integer> appCounts = new LinkedHashMap<>();
        for (KuudraManifest.Component component : resources.components().values()) {
            String reference = componentReference(component.type(), component.component());
            PluginComponentDefinition definition = requirePlugins().components().find(reference)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown component: " + reference));
            if (!definition.kind().prefix().equals(component.type())) throw new IllegalArgumentException("Component type mismatch: " + component.id());
            if (definition.instancePolicy().limitScope() == ComponentLimitScope.APP) {
                int count = appCounts.merge(definition.instancePolicy().exclusivityDomain(), 1, Integer::sum);
                if (count > definition.instancePolicy().maxInstances()) throw new IllegalArgumentException("Component instance limit exceeded for domain " + definition.instancePolicy().exclusivityDomain());
            }
        }
        Map<KuudraManifest.ResourceId, Integer> usages = new LinkedHashMap<>();
        for (KuudraManifest.Flow flow : resources.flows().values()) {
            Map<String, Integer> flowCounts = new LinkedHashMap<>();
            for (KuudraManifest.ResourceReference reference : flow.imports().values()) {
                KuudraManifest.Component component = resources.components().get(reference.id());
                if (component == null) continue;
                PluginComponentDefinition definition = requirePlugins().components().find(componentReference(component.type(), component.component())).orElseThrow();
                usages.merge(component.id(), 1, Integer::sum);
                if (definition.instancePolicy().limitScope() == ComponentLimitScope.FLOW) {
                    int count = flowCounts.merge(definition.instancePolicy().exclusivityDomain(), 1, Integer::sum);
                    if (count > definition.instancePolicy().maxInstances()) throw new IllegalArgumentException("Flow component instance limit exceeded for domain " + definition.instancePolicy().exclusivityDomain());
                }
            }
        }
        for (Map.Entry<KuudraManifest.ResourceId, Integer> usage : usages.entrySet()) if (usage.getValue() > 1) {
            KuudraManifest.Component component = resources.components().get(usage.getKey());
            PluginComponentDefinition definition = requirePlugins().components().find(componentReference(component.type(), component.component())).orElseThrow();
            if (!definition.instancePolicy().shareable() || !definition.instancePolicy().threadSafe())
                throw new IllegalArgumentException("Component imported by multiple Flows is not shareable and thread-safe: " + component.id());
        }
    }

    private IngressConfiguration ingressConfiguration(Map<String,Object> options) {
        KuudraConfig.SessionCoordinatorSettings defaults = bootstrapConfig == null
                ? new KuudraConfig.SessionCoordinatorSettings(SessionSchedulingPolicy.PARALLEL, SessionGroupScope.FLOW_BINDING, 64, 256)
                : bootstrapConfig.runtime().sessionCoordinator();
        return new IngressConfiguration(SessionSchedulingPolicy.valueOf(text(options.getOrDefault("policy", defaults.defaultPolicy())).replace('-', '_').toUpperCase(java.util.Locale.ROOT)),
                SessionGroupScope.valueOf(text(options.getOrDefault("groupScope", options.getOrDefault("group-scope", defaults.defaultGroupScope()))).replace('-', '_').toUpperCase(java.util.Locale.ROOT)),
                Integer.parseInt(text(options.getOrDefault("maxParallelSessions", options.getOrDefault("max-parallel-sessions", defaults.maxParallelSessions())))),
                Integer.parseInt(text(options.getOrDefault("queueCapacity", options.getOrDefault("queue-capacity", defaults.queueCapacity())))));
    }
    private static String text(Object value) { if (value == null) throw new IllegalArgumentException("Configuration value must not be null"); return value.toString(); }
    private static String componentReference(String type, String component) {
        if (component.startsWith(type + "/")) return component; // Compatibility with the former full reference syntax.
        String[] parts = component.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) throw new IllegalArgumentException("Component must be namespace/component-id: " + component);
        return type + "/" + component;
    }
    public record Flow(String id, int activeSessions, int deferredTasks, boolean selected) { }
    public record KernelCheckpoint(RuntimeCheckpoint runtime, List<ComponentResource> components) {
        public KernelCheckpoint { components = List.copyOf(components); }
    }
    public record Session(UUID id, String flowId, long flowRevision, String ingressId, String groupKey, String status, boolean cancellationRequested, int activeLeases) { }
    public record Resource(String flowId, String id, String type, String component, String target, String status) { }
    public record ComponentResource(String kind, String namespace, String name, String component,
                                    String desiredState, String status, String effectiveStatus, boolean available,
                                    List<String> suspensionReasons, boolean selected, List<String> importedBy,
                                    List<String> lifecycleCapabilities) {
        public ComponentResource {
            suspensionReasons = List.copyOf(suspensionReasons);
            importedBy = List.copyOf(importedBy);
            lifecycleCapabilities = List.copyOf(lifecycleCapabilities);
        }
    }
    public record Plugin(String id, String namespace, String version, String status, List<Dependency> dependencies,
                         List<Component> components) {
        public Plugin { dependencies = List.copyOf(dependencies); components = List.copyOf(components); }
    }
    public record Dependency(String namespace, String pluginId, boolean mandatory, String versionRange) { }
    public record Component(String reference, String pluginId, String namespace, String kind, String name,
                            String implementation, InstancePolicy instancePolicy, ComponentDocumentation documentation) { }
    public record InstancePolicy(int maxInstances, String limitScope, String exclusivityDomain,
                                 boolean shareable, boolean threadSafe) { }
    public record ComponentDocumentation(String purpose, boolean lifecycle,
                                         List<String> lifecyclePhases, List<String> supportedDesiredStates,
                                         List<ConfigurationDocumentation> configuration,
                                         List<EventDocumentation> emittedEvents) {
        public ComponentDocumentation {
            lifecyclePhases = List.copyOf(lifecyclePhases);
            supportedDesiredStates = List.copyOf(supportedDesiredStates);
            configuration = List.copyOf(configuration);
            emittedEvents = List.copyOf(emittedEvents);
        }
    }
    public record ConfigurationDocumentation(String path, String type, boolean required, String defaultValue,
                                             String description, List<Object> examples, List<String> allowedValues) {
        public ConfigurationDocumentation {
            examples = List.copyOf(examples);
            allowedValues = List.copyOf(allowedValues);
        }
    }
    public record EventDocumentation(String stage, String eventType, String description, String dataExample) { }
    public record ResourceDocumentation(String apiVersion, String namespace, String kind, String purpose,
                                        List<ResourceFieldDocumentation> fields, List<Map<String,Object>> examples) {
        public ResourceDocumentation { fields = List.copyOf(fields); examples = List.copyOf(examples); }
    }
    public record ResourceFieldDocumentation(String path, String type, boolean required, String description,
                                             List<Object> examples) {
        public ResourceFieldDocumentation { examples = List.copyOf(examples); }
    }
    private record LoadedConfiguration(KuudraConfig.RuntimeConfig config, List<HomeInitialization> homeInitializations) { }
    private record HomeInitialization(String role, Path path) { }
    private ManagedEventSource requireEventSource(String flowId, String resourceId) {
        ManagedEventSource source = eventSources.get(new ResourceKey(flowId, resourceId));
        if (source == null) throw new IllegalArgumentException("Unknown EventSource resource: " + flowId + "/" + resourceId);
        return source;
    }
    private ComponentResource componentResource(KuudraManifest.Component component) {
        List<String> importedBy = desiredResources().flows().values().stream().filter(flow -> flow.imports().values().stream()
                .anyMatch(reference -> reference.id().equals(component.id()))).map(flow -> flow.id().qualifiedName()).toList();
        boolean source = component.type().equals("event-source");
        Object instance = manifestInstances.get(component.id());
        boolean kernelPaused = status == AppStatus.PAUSING || status == AppStatus.PAUSED;
        String observed = runtime == null ? "NOT_RUNNING"
                : manifestObservedStates.getOrDefault(component.id(), source ? "STOPPED" : "INACTIVE");
        boolean operational = observed.equals("RUNNING") || observed.equals("ACTIVE");
        String effective = kernelPaused && operational ? "SUSPENDED" : observed;
        List<String> suspensionReasons = kernelPaused && operational ? List.of("KERNEL")
                : observed.equals("PAUSED") ? List.of("COMPONENT") : List.of();
        boolean available = operational && !kernelPaused;
        List<String> capabilities = new ArrayList<>(instance instanceof Lifecycle || source
                ? List.of("start", "stop") : List.of("materialize", "destroy"));
        if (instance instanceof PausableLifecycle) capabilities.addAll(List.of("pause", "resume"));
        boolean selected = selected(component.metadata().namespace());
        if (!selected) { observed = "EXCLUDED"; effective = "EXCLUDED"; available = false; suspensionReasons = List.of(); }
        return new ComponentResource(component.id().kind(), component.metadata().namespace(), component.metadata().name(),
                component.component(), component.desiredState(), observed, effective, available, suspensionReasons, selected, importedBy,
                capabilities);
    }
    private record ResourceKey(String flowId, String id) { }

    /** StateStore is the control-plane source of truth; the in-memory copy only covers an active run. */
    private KuudraManifest.Resources desiredResources() {
        return stateStore == null ? manifestResources : stateStore.desiredResources();
    }
    private static final class ManagedEventSource {
        private final ResourceKey key; private final String componentReference; private final String targetNodeId;
        private EventSource source; private SourceRegistration registration;
        private ManagedEventSource(ResourceKey key, String componentReference, String targetNodeId, EventSource source) { this.key = key; this.componentReference = componentReference; this.targetNodeId = targetNodeId; this.source = source; }
        private Resource snapshot() { return new Resource(key.flowId, key.id, "event-source", componentReference == null ? "<programmatic>" : componentReference, targetNodeId, registration == null ? "STOPPED" : "RUNNING"); }
    }
}
