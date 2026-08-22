package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.AppLifecycle;
import io.github.actforever.kuudra.api.AppSnapshot;
import io.github.actforever.kuudra.api.AppStatus;
import io.github.actforever.kuudra.api.Actor;
import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.EventAdapter;
import io.github.actforever.kuudra.api.EventProcessor;
import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.api.FlowSnapshot;
import io.github.actforever.kuudra.api.SessionSnapshot;
import io.github.actforever.kuudra.api.SourceRegistration;
import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.api.ParentTerminationPolicy;
import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;
import io.github.actforever.kuudra.plugin.DefaultPluginManager;
import io.github.actforever.kuudra.plugin.PluginArchiveLoader;
import io.github.actforever.kuudra.plugin.PluginComponentRegistry;
import io.github.actforever.kuudra.config.KuudraConfig;
import io.github.actforever.kuudra.config.KuudraConfigResource;
import io.github.actforever.kuudra.config.KuudraYamlLoader;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;
import io.github.actforever.kuudra.runtime.SimpleSystemEventBus;
import io.github.actforever.kuudra.logging.KuudraLog;
import io.github.actforever.kuudra.logging.KuudraLogSession;

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
    private final int queueCapacity;
    private final int workerThreads;
    private final KuudraConfig.RuntimeConfig bootstrapConfig;
    private Map<String, Object> globalContext = Map.of();
    private final SystemEventBus events = new SimpleSystemEventBus();
    private final List<PluginArchiveLoader.LoadedArchive> archives = new ArrayList<>();
    private final Map<ResourceKey, ManagedEventSource> eventSources = new LinkedHashMap<>();
    private KuudraRuntime runtime;
    private DefaultPluginManager plugins;
    private AutoCloseable runtimeEvents;
    private KuudraLogSession logSession;
    private AppStatus status = AppStatus.NEW;
    private String detail = "not started";

    public KuudraApp(int queueCapacity, int workerThreads) { this(queueCapacity, workerThreads, null); }
    private KuudraApp(int queueCapacity, int workerThreads, KuudraConfig.RuntimeConfig bootstrapConfig) { this.queueCapacity = queueCapacity; this.workerThreads = workerThreads; this.bootstrapConfig = bootstrapConfig; start(); }
    public static KuudraApp createDefault() { return new KuudraApp(1_024, Math.max(2, Runtime.getRuntime().availableProcessors() / 2)); }
    public static KuudraApp createConfigured(Path configFile) throws IOException {
        KuudraConfigResource explicit = KuudraYamlLoader.readResource(configFile);
        KuudraConfig.RuntimeConfig config = loadConfiguration(explicit.baseDirectory(), explicit);
        return new KuudraApp(config.runtime().queueCapacity(), config.runtime().workerThreads(), config);
    }
    /** Creates App using a programmatic configuration as the highest-priority layer. */
    public static KuudraApp createConfigured(KuudraConfigResource resource) throws IOException {
        KuudraConfig.RuntimeConfig config = loadConfiguration(resource.baseDirectory(), resource);
        return new KuudraApp(config.runtime().queueCapacity(), config.runtime().workerThreads(), config);
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
        Path base = baseDirectory.toAbsolutePath().normalize();
        KuudraConfig.RuntimeConfig config = loadConfiguration(base, null);
        return new KuudraApp(config.runtime().queueCapacity(), config.runtime().workerThreads(), config);
    }

    private static KuudraConfig.RuntimeConfig loadConfiguration(Path baseDirectory, KuudraConfigResource explicit) throws IOException {
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
            initializeHomeDirectory(homeDirectory, defaultConfiguration);
            KuudraConfigResource homeConfig = KuudraYamlLoader.readResource(homeDirectory.resolve("config.yaml"));
            KuudraConfigResource merged = KuudraYamlLoader.merge(base, "merged Kuudra configuration", defaults, homeConfig, explicit);
            return KuudraYamlLoader.load(merged);
        }
    }

    private static void initializeHomeDirectory(Path homeDirectory, byte[] defaultConfiguration) throws IOException {
        Files.createDirectories(homeDirectory);
        Files.createDirectories(homeDirectory.resolve("plugins"));
        Files.createDirectories(homeDirectory.resolve("flows"));
        Files.createDirectories(homeDirectory.resolve("logs"));
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
        }
    }

    @Override public synchronized void start() {
        if (status == AppStatus.RUNNING || status == AppStatus.STARTING) return;
        try {
            Path home = bootstrapConfig == null ? Path.of(".kuudra") : bootstrapConfig.homeDirectory();
            logSession = KuudraLog.openSession(home.resolve("logs"), events);
            status = AppStatus.STARTING; publish("app.starting");
            KuudraBanner.print();
            globalContext = bootstrapConfig == null ? Map.of() : bootstrapConfig.globalContext();
            runtime = new KuudraRuntime(queueCapacity, workerThreads, globalContext);
            runtimeEvents = runtime.systemEvents().subscribe(events::publish);
            events.publish(SystemEvent.of("runtime.started", Map.of("queueCapacity", queueCapacity, "workerThreads", workerThreads)));
            Path homes = bootstrapConfig == null ? Path.of(".kuudra", "plugins") : bootstrapConfig.homeDirectory().resolve("plugins");
            plugins = new DefaultPluginManager(homes, runtime::registerSource, events);
            status = AppStatus.RUNNING;
            if (bootstrapConfig != null) applyConfiguration(bootstrapConfig);
            detail = ""; publish("app.running");
        } catch (RuntimeException failure) {
            releaseResources();
            status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed"); closeLogSession(); throw failure;
        } catch (IOException failure) {
            status = AppStatus.FAILED; detail = failure.toString(); closeLogSession(); throw new IllegalStateException("Failed to initialize Kuudra logging", failure);
        }
    }

    @Override public synchronized void stop() {
        if (status == AppStatus.STOPPED || status == AppStatus.NEW) return;
        status = AppStatus.STOPPING; publish("app.stopping");
        try {
            releaseResources();
            status = AppStatus.STOPPED; detail = ""; publish("app.stopped");
            closeLogSession();
        } catch (RuntimeException failure) {
            status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed"); throw failure;
        }
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
    public Health health() { AppSnapshot snapshot = snapshot(); return new Health(snapshot.status().name(), snapshot.queuedTasks(), snapshot.flowCount()); }
    public synchronized Map<String, Object> globalContext() { return globalContext; }

    public List<Flow> flows() { return requireRuntime().flows().stream().map(KuudraApp::flow).toList(); }
    public Optional<Flow> flow(String flowId) { return requireRuntime().flow(flowId).map(KuudraApp::flow); }
    public void activateFlow(String flowId) { requireRuntime().activateFlow(flowId); }
    public void pauseFlow(String flowId) { requireRuntime().pauseFlow(flowId); }
    public void resumeFlow(String flowId) { requireRuntime().resumeFlow(flowId); }
    public void stopFlow(String flowId) { requireRuntime().stopFlow(flowId); }
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
    public Optional<Session> session(UUID sessionId) { return requireRuntime().session(sessionId).map(KuudraApp::session); }
    public boolean cancelSession(UUID sessionId) { return requireRuntime().cancel(sessionId); }
    public void registerFlow(KuudraFlow flow) { requireRuntime().registerFlow(flow); }
    public boolean publish(String flowId, String targetNodeId, Event event) { return requireRuntime().publish(flowId, targetNodeId, event); }
    public boolean awaitNoActiveSessions(Duration timeout) throws InterruptedException { return requireRuntime().awaitNoActiveSessions(timeout); }

    public synchronized void loadPlugin(Path archive) throws IOException {
        events.publish(SystemEvent.of("plugin.archive.loading", Map.of("archive", archive.toAbsolutePath().normalize().toString())));
        PluginArchiveLoader.LoadedArchive loaded = new PluginArchiveLoader().load(archive, KuudraApp.class.getClassLoader());
        try {
            requirePlugins().register(loaded.plugin()); archives.add(loaded);
            events.publish(SystemEvent.of("plugin.archive.loaded", Map.of("archive", archive.toAbsolutePath().normalize().toString(), "pluginId", loaded.plugin().metadata().id())));
        }
        catch (RuntimeException error) { try { loaded.close(); } catch (IOException closeError) { error.addSuppressed(closeError); } throw error; }
    }
    public java.util.concurrent.CompletionStage<Void> startPlugins() { return requirePlugins().startAll(); }
    public PluginComponentRegistry pluginComponents() { return requirePlugins().components(); }
    public java.util.concurrent.CompletionStage<SourceRegistration> installEventSource(String componentReference, String flowId, String targetNodeId) {
        EventSource source = requirePlugins().createComponent(componentReference, EventSource.class);
        return requireRuntime().registerSource(flowId, targetNodeId, source);
    }

    private KuudraRuntime requireRuntime() { synchronized (this) { if (status != AppStatus.RUNNING || runtime == null) throw new IllegalStateException("App is not running: " + status); return runtime; } }
    private DefaultPluginManager requirePlugins() { synchronized (this) { if (status != AppStatus.RUNNING || plugins == null) throw new IllegalStateException("App is not running: " + status); return plugins; } }
    private void publish(String type) { events.publish(SystemEvent.of(type, java.util.Map.of("status", status.name(), "detail", detail))); }
    @Override public void close() { stop(); }
    private static Flow flow(FlowSnapshot snapshot) { return new Flow(snapshot.flowId(), snapshot.status().name(), snapshot.activeSessions(), snapshot.deferredTasks()); }
    private static Session session(SessionSnapshot snapshot) { return new Session(snapshot.id(), snapshot.flowId(), snapshot.name(), snapshot.admissionKey(), snapshot.status().name(), snapshot.cancellationRequested(), snapshot.parentSessionIds()); }
    public record Health(String status, int queuedTasks, int flows) { }
    public record Status(AppSnapshot app, List<Flow> flows, int activeSessions) {
        public Status { flows = List.copyOf(flows); }
    }
    public synchronized void loadPluginArchives(List<Path> pluginArchives) throws IOException {
        List<PluginArchiveLoader.LoadedArchive> loaded = new PluginArchiveLoader().loadAll(pluginArchives, KuudraApp.class.getClassLoader());
        try {
            for (PluginArchiveLoader.LoadedArchive archive : loaded) requirePlugins().register(archive.plugin());
            archives.addAll(loaded);
        } catch (RuntimeException error) {
            for (PluginArchiveLoader.LoadedArchive archive : loaded) try { archive.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    /** Loads plugin archives, resolves their metadata dependencies, and assembles every configured Event Flow. */
    private void applyConfiguration(KuudraConfig.RuntimeConfig config) {
        try {
            Path pluginDirectory = config.homeDirectory().resolve("plugins");
            Files.createDirectories(pluginDirectory);
            events.publish(SystemEvent.of("plugin.scan.started", Map.of("directory", pluginDirectory.toString())));
            List<Path> pluginArchives;
            try (var files = Files.list(pluginDirectory)) {
                pluginArchives = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        .sorted().toList();
            }
            events.publish(SystemEvent.of("plugin.scan.completed", Map.of("directory", pluginDirectory.toString(), "archives", pluginArchives.size())));
            loadPluginArchives(pluginArchives);
            startPlugins().toCompletableFuture().join();
            for (KuudraConfig.FlowConfig flow : config.flows().values()) registerFlow(compile(flow));
            for (KuudraConfig.FlowConfig flow : config.flows().values()) {
                for (KuudraConfig.SourceBinding source : flow.sources()) {
                    ResourceKey key = new ResourceKey(flow.id(), source.id());
                    if (eventSources.putIfAbsent(key, new ManagedEventSource(key, source.component(), source.targetNodeId(), null)) != null) throw new IllegalArgumentException("Duplicate EventSource resource: " + flow.id() + "/" + source.id());
                    if (source.enabled()) startEventSource(flow.id(), source.id());
                }
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Failed to apply Kuudra configuration", error);
        }
    }

    private void releaseResources() {
        if (runtime != null) try { runtime.close(); } catch (RuntimeException ignored) { }
        if (plugins != null) try { plugins.close(); } catch (RuntimeException ignored) { }
        for (PluginArchiveLoader.LoadedArchive archive : archives) try { archive.close(); } catch (IOException ignored) { }
        archives.clear();
        eventSources.clear();
        if (runtimeEvents != null) try { runtimeEvents.close(); } catch (Exception ignored) { }
        runtime = null;
        plugins = null;
        runtimeEvents = null;
        globalContext = Map.of();
    }

    private void closeLogSession() {
        if (logSession == null) return;
        try { logSession.close(); } finally { logSession = null; }
    }

    private KuudraFlow compile(KuudraConfig.FlowConfig definition) {
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        for (KuudraConfig.NodeConfig node : definition.nodes().values()) {
            FlowNode compiled = switch (node.type()) {
                case "event-adapter" -> new FlowNode.AdapterNode(node.id(), requirePlugins().createComponent(componentReference(node.type(), node.component()), EventAdapter.class), node.options());
                case "event-processor" -> new FlowNode.ProcessorNode(node.id(), requirePlugins().createComponent(componentReference(node.type(), node.component()), EventProcessor.class), node.options());
                case "actor" -> new FlowNode.ActorNode(node.id(), requirePlugins().createComponent(componentReference(node.type(), node.component()), Actor.class), node.options());
                case "session-allocator" -> new FlowNode.AllocatorNode(node.id(), sessionSpec(node));
                default -> throw new IllegalArgumentException("Unsupported Flow node type: " + node.type());
            };
            nodes.put(node.id(), compiled);
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (KuudraConfig.EdgeConfig edge : definition.edges()) edges.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
        return new KuudraFlow(definition.id(), nodes, edges);
    }
    private static SessionSpec sessionSpec(KuudraConfig.NodeConfig node) {
        Map<String, Object> options = node.options();
        String name = text(options.getOrDefault("name", node.id()));
        String admissionKey = text(options.getOrDefault("admission-key", "default"));
        SessionPolicy policy = SessionPolicy.valueOf(text(options.getOrDefault("policy", SessionPolicy.PARALLEL.name())));
        ParentTerminationPolicy parent = ParentTerminationPolicy.valueOf(text(options.getOrDefault("parent-termination-policy", ParentTerminationPolicy.NONE.name())));
        return new SessionSpec(name, admissionKey, policy, parent);
    }
    private static String text(Object value) { if (value == null) throw new IllegalArgumentException("Configuration value must not be null"); return value.toString(); }
    private static String componentReference(String type, String component) {
        if (component.startsWith(type + "/")) return component; // Compatibility with the former full reference syntax.
        String[] parts = component.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) throw new IllegalArgumentException("Component must be namespace/component-id: " + component);
        return type + "/" + component;
    }
    public record Flow(String id, String status, int activeSessions, int deferredTasks) { }
    public record Session(UUID id, String flowId, String name, String admissionKey, String status, boolean cancellationRequested, java.util.Set<UUID> parentSessionIds) { }
    public record Resource(String flowId, String id, String type, String component, String target, String status) { }
    private ManagedEventSource requireEventSource(String flowId, String resourceId) {
        ManagedEventSource source = eventSources.get(new ResourceKey(flowId, resourceId));
        if (source == null) throw new IllegalArgumentException("Unknown EventSource resource: " + flowId + "/" + resourceId);
        return source;
    }
    private record ResourceKey(String flowId, String id) { }
    private static final class ManagedEventSource {
        private final ResourceKey key; private final String componentReference; private final String targetNodeId;
        private EventSource source; private SourceRegistration registration;
        private ManagedEventSource(ResourceKey key, String componentReference, String targetNodeId, EventSource source) { this.key = key; this.componentReference = componentReference; this.targetNodeId = targetNodeId; this.source = source; }
        private Resource snapshot() { return new Resource(key.flowId, key.id, "event-source", componentReference == null ? "<programmatic>" : componentReference, targetNodeId, registration == null ? "STOPPED" : "RUNNING"); }
    }
}
