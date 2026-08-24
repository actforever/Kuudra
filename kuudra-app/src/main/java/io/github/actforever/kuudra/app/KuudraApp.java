package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.AppLifecycle;
import io.github.actforever.kuudra.api.AppSnapshot;
import io.github.actforever.kuudra.api.AppStatus;
import io.github.actforever.kuudra.api.*;
import io.github.actforever.kuudra.api.FlowSnapshot;
import io.github.actforever.kuudra.api.SessionSnapshot;
import io.github.actforever.kuudra.api.SourceRegistration;
import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;
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
import io.github.actforever.kuudra.runtime.SimpleSystemEventBus;
import io.github.actforever.kuudra.logging.KuudraLog;
import io.github.actforever.kuudra.logging.KuudraLogConfiguration;
import io.github.actforever.kuudra.logging.KuudraLogLevel;
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
    private final Map<KuudraManifest.ResourceId, Object> manifestInstances = new LinkedHashMap<>();
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
        Files.createDirectories(homeDirectory.resolve("manifests"));
        Files.createDirectories(homeDirectory.resolve("logs"));
        Files.createDirectories(homeDirectory.resolve("state"));
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
            KuudraLogConfiguration logging = bootstrapConfig == null
                    ? KuudraLogConfiguration.DEFAULT
                    : new KuudraLogConfiguration(KuudraLogLevel.valueOf(bootstrapConfig.logging().level()),
                    bootstrapConfig.logging().consoleEnabled(), bootstrapConfig.logging().fileEnabled());
            logSession = KuudraLog.openSession(home.resolve("logs"), events, logging);
            status = AppStatus.STARTING; publish("app.starting");
            KuudraBanner.print();
            globalContext = bootstrapConfig == null ? Map.of() : bootstrapConfig.globalContext();
            int maxEventHops = bootstrapConfig == null ? 256 : bootstrapConfig.runtime().maxEventHops();
            runtime = new KuudraRuntime(queueCapacity, workerThreads, globalContext, maxEventHops);
            runtimeEvents = runtime.systemEvents().subscribe(events::publish);
            events.publish(SystemEvent.of("runtime.started", Map.of("queueCapacity", queueCapacity, "workerThreads", workerThreads, "maxEventHops", maxEventHops)));
            Path homes = bootstrapConfig == null ? Path.of(".kuudra", "plugins") : bootstrapConfig.homeDirectory().resolve("plugins");
            plugins = new DefaultPluginManager(homes, runtime::registerSource, events);
            status = AppStatus.RUNNING;
            if (bootstrapConfig != null) applyConfiguration(bootstrapConfig);
            detail = ""; publish("app.running");
        } catch (RuntimeException failure) {
            releaseResources();
            status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed"); closeLogSession(); throw failure;
        } catch (IOException failure) {
            status = AppStatus.FAILED; detail = failure.toString(); closeLogSession(); throw new KuudraException("Failed to initialize Kuudra logging", failure);
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
    public boolean publish(String flowId, String targetNodeId, KuudraEvent event) { return requireRuntime().publish(flowId, targetNodeId, event); }
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

    private KuudraRuntime requireRuntime() { synchronized (this) { if (status != AppStatus.RUNNING || runtime == null) throw new KuudraException("App is not running: " + status); return runtime; } }
    private DefaultPluginManager requirePlugins() { synchronized (this) { if (status != AppStatus.RUNNING || plugins == null) throw new KuudraException("App is not running: " + status); return plugins; } }
    private void publish(String type) { events.publish(SystemEvent.of(type, java.util.Map.of("status", status.name(), "detail", detail))); }
    @Override public void close() { stop(); }
    private static Flow flow(FlowSnapshot snapshot) { return new Flow(snapshot.flowId(), snapshot.status().name(), snapshot.activeSessions(), snapshot.deferredTasks()); }
    private static Session session(SessionSnapshot snapshot) { return new Session(snapshot.id(), snapshot.flowId(), snapshot.flowRevision(), snapshot.ingressId(), snapshot.groupKey(), snapshot.status().name(), snapshot.cancellationRequested(), snapshot.activeLeases()); }
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
            applyManifests(config.manifests());
        } catch (IOException | RuntimeException error) {
            throw KuudraException.wrap("Failed to apply Kuudra configuration", error);
        }
    }

    private void releaseResources() {
        if (runtime != null) try { runtime.close(); } catch (RuntimeException ignored) { }
        if (plugins != null) try { plugins.close(); } catch (RuntimeException ignored) { }
        for (PluginArchiveLoader.LoadedArchive archive : archives) try { archive.close(); } catch (IOException ignored) { }
        archives.clear();
        eventSources.clear();
        manifestInstances.clear();
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


    private void applyManifests(KuudraManifest.Resources resources) {
        if (resources.isEmpty()) return;
        validateManifestPolicies(resources);
        for (KuudraManifest.Component component : resources.components().values()) {
            Object instance = switch (component.type()) {
                case "event-source" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventSource.class, component.options());
                case "event-adapter" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventAdapter.class, component.options());
                case "event-interpreter" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventInterpreter.class, component.options());
                case "event-handler" -> requirePlugins().createComponent(componentReference(component.type(), component.component()), EventHandler.class, component.options());
                case "ingress" -> core(component) ? component : requirePlugins().createComponent(componentReference(component.type(), component.component()), Ingress.class, component.options());
                case "egress" -> core(component) ? component : requirePlugins().createComponent(componentReference(component.type(), component.component()), Egress.class, component.options());
                default -> throw new IllegalArgumentException("Unsupported Component type: " + component.type());
            };
            manifestInstances.put(component.id(), instance);
        }
        Map<KuudraManifest.ResourceId, List<KuudraRuntime.SourceTarget>> sourceTargets = new LinkedHashMap<>();
        for (KuudraManifest.Flow flow : resources.flows().values()) {
            registerFlow(compile(flow, resources.components(), sourceTargets));
            switch (flow.desiredState().toLowerCase(java.util.Locale.ROOT)) {
                case "active", "running" -> { }
                case "paused" -> pauseFlow(flow.id().qualifiedName());
                case "stopped" -> stopFlow(flow.id().qualifiedName());
                default -> throw new IllegalArgumentException("Unsupported Flow desiredState: " + flow.desiredState());
            }
        }
        for (Map.Entry<KuudraManifest.ResourceId, List<KuudraRuntime.SourceTarget>> entry : sourceTargets.entrySet()) {
            KuudraManifest.Component component = resources.components().get(entry.getKey());
            String desired = component.desiredState().toLowerCase(java.util.Locale.ROOT);
            if (desired.equals("stopped") || desired.equals("disabled")) continue;
            if (!desired.equals("running") && !desired.equals("active")) throw new IllegalArgumentException("Unsupported EventSource desiredState: " + component.desiredState());
            EventSource source = (EventSource) manifestInstances.get(entry.getKey());
            requireRuntime().registerSource(entry.getValue(), source).toCompletableFuture().join();
        }
    }

    private KuudraFlow compile(KuudraManifest.Flow definition,
                               Map<KuudraManifest.ResourceId, KuudraManifest.Component> components,
                               Map<KuudraManifest.ResourceId, List<KuudraRuntime.SourceTarget>> sourceTargets) {
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, KuudraManifest.ResourceReference> imported : definition.imports().entrySet()) {
            KuudraManifest.Component component = components.get(imported.getValue().id());
            if (component == null) throw new IllegalArgumentException("Unknown imported Component: " + imported.getValue().id());
            Object instance = manifestInstances.get(component.id());
            FlowNode node = switch (component.type()) {
                case "event-source" -> null;
                case "event-adapter" -> new FlowNode.AdapterNode(imported.getKey(), (EventAdapter) instance, domain(component.options()), component.options());
                case "event-interpreter" -> new FlowNode.InterpreterNode(imported.getKey(), (EventInterpreter) instance, component.options());
                case "event-handler" -> new FlowNode.HandlerNode(imported.getKey(), (EventHandler) instance, component.options());
                case "ingress" -> new FlowNode.IngressNode(imported.getKey(), component.id().qualifiedName(), ingress(component, instance), ingressConfiguration(component.options()), component.options());
                case "egress" -> new FlowNode.EgressNode(imported.getKey(), egress(component, instance), component.options());
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

    private void validateManifestPolicies(KuudraManifest.Resources resources) {
        Map<String, Integer> appCounts = new LinkedHashMap<>();
        for (KuudraManifest.Component component : resources.components().values()) {
            if (core(component)) continue;
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
                if (component == null || core(component)) continue;
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

    private static EventDomain domain(Map<String,Object> options) { return EventDomain.valueOf(text(options.getOrDefault("domain", "RAW")).toUpperCase(java.util.Locale.ROOT)); }
    private static Ingress ingress(KuudraManifest.Component component, Object instance) {
        if (!core(component)) return (Ingress) instance;
        return (event, context) -> IngressDecision.accept(text(context.configuration().getOrDefault("groupKey", context.configuration().getOrDefault("group-key", event.type()))), event);
    }
    private static Egress egress(KuudraManifest.Component component, Object instance) { return core(component) ? (event, context) -> List.of(event) : (Egress) instance; }
    private IngressConfiguration ingressConfiguration(Map<String,Object> options) {
        KuudraConfig.SessionCoordinatorSettings defaults = bootstrapConfig == null
                ? new KuudraConfig.SessionCoordinatorSettings(SessionSchedulingPolicy.PARALLEL, SessionGroupScope.FLOW_BINDING, 64, 256)
                : bootstrapConfig.runtime().sessionCoordinator();
        return new IngressConfiguration(SessionSchedulingPolicy.valueOf(text(options.getOrDefault("policy", defaults.defaultPolicy())).replace('-', '_').toUpperCase(java.util.Locale.ROOT)),
                SessionGroupScope.valueOf(text(options.getOrDefault("groupScope", options.getOrDefault("group-scope", defaults.defaultGroupScope()))).replace('-', '_').toUpperCase(java.util.Locale.ROOT)),
                Integer.parseInt(text(options.getOrDefault("maxParallelSessions", options.getOrDefault("max-parallel-sessions", defaults.maxParallelSessions())))),
                Integer.parseInt(text(options.getOrDefault("queueCapacity", options.getOrDefault("queue-capacity", defaults.queueCapacity())))));
    }
    private static boolean core(KuudraManifest.Component component) { return (component.type().equals("ingress") || component.type().equals("egress")) && component.component().equals("core/default"); }
    private static String text(Object value) { if (value == null) throw new IllegalArgumentException("Configuration value must not be null"); return value.toString(); }
    private static String componentReference(String type, String component) {
        if (component.startsWith(type + "/")) return component; // Compatibility with the former full reference syntax.
        String[] parts = component.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) throw new IllegalArgumentException("Component must be namespace/component-id: " + component);
        return type + "/" + component;
    }
    public record Flow(String id, String status, int activeSessions, int deferredTasks) { }
    public record Session(UUID id, String flowId, long flowRevision, String ingressId, String groupKey, String status, boolean cancellationRequested, int activeLeases) { }
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
