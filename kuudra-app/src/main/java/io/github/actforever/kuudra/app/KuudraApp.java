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
import io.github.actforever.kuudra.config.KuudraYamlLoader;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;
import io.github.actforever.kuudra.runtime.SimpleSystemEventBus;

import java.io.IOException;
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
    private KuudraRuntime runtime;
    private DefaultPluginManager plugins;
    private AutoCloseable runtimeEvents;
    private AppStatus status = AppStatus.NEW;
    private String detail = "not started";

    public KuudraApp(int queueCapacity, int workerThreads) { this(queueCapacity, workerThreads, null); }
    private KuudraApp(int queueCapacity, int workerThreads, KuudraConfig.RuntimeConfig bootstrapConfig) { this.queueCapacity = queueCapacity; this.workerThreads = workerThreads; this.bootstrapConfig = bootstrapConfig; start(); }
    public static KuudraApp createDefault() { return new KuudraApp(1_024, Math.max(2, Runtime.getRuntime().availableProcessors() / 2)); }
    public static KuudraApp createConfigured(Path configFile) throws IOException {
        KuudraConfig.RuntimeConfig config = KuudraYamlLoader.load(configFile);
        return new KuudraApp(config.runtime().queueCapacity(), config.runtime().workerThreads(), config);
    }

    @Override public synchronized void start() {
        if (status == AppStatus.RUNNING || status == AppStatus.STARTING) return;
        status = AppStatus.STARTING; publish("app.starting");
        try {
            KuudraBanner.print();
            runtime = new KuudraRuntime(queueCapacity, workerThreads);
            runtimeEvents = runtime.systemEvents().subscribe(events::publish);
            plugins = new DefaultPluginManager(Path.of("plugins", "homes"), runtime::registerSource);
            status = AppStatus.RUNNING;
            if (bootstrapConfig != null) applyConfiguration(bootstrapConfig);
            detail = ""; publish("app.running");
        } catch (RuntimeException failure) {
            releaseResources();
            status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed"); throw failure;
        }
    }

    @Override public synchronized void stop() {
        if (status == AppStatus.STOPPED || status == AppStatus.NEW) return;
        status = AppStatus.STOPPING; publish("app.stopping");
        try {
            releaseResources();
            status = AppStatus.STOPPED; detail = ""; publish("app.stopped");
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
    public Optional<Session> session(UUID sessionId) { return requireRuntime().session(sessionId).map(KuudraApp::session); }
    public boolean cancelSession(UUID sessionId) { return requireRuntime().cancel(sessionId); }
    public void registerFlow(KuudraFlow flow) { requireRuntime().registerFlow(flow); }
    public boolean publish(String flowId, String targetNodeId, Event event) { return requireRuntime().publish(flowId, targetNodeId, event); }
    public boolean awaitNoActiveSessions(Duration timeout) throws InterruptedException { return requireRuntime().awaitNoActiveSessions(timeout); }

    public synchronized void loadPlugin(Path archive) throws IOException {
        PluginArchiveLoader.LoadedArchive loaded = new PluginArchiveLoader().load(archive, KuudraApp.class.getClassLoader());
        try { requirePlugins().register(loaded.plugin()); archives.add(loaded); }
        catch (RuntimeException error) { try { loaded.close(); } catch (IOException closeError) { error.addSuppressed(closeError); } throw error; }
    }
    public java.util.concurrent.CompletionStage<Void> startPlugins() { return requirePlugins().startAll(); }
    public PluginComponentRegistry pluginComponents() { return requirePlugins().components(); }
    public java.util.concurrent.CompletionStage<SourceRegistration> installEventSource(String componentReference, String flowId, String targetNodeId) {
        EventSource source = requirePlugins().components().create(componentReference, EventSource.class);
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

    /** Loads plugin archives, resolves their metadata dependencies, and assembles every configured Event Flow. */
    private void applyConfiguration(KuudraConfig.RuntimeConfig config) {
        try {
            globalContext = Map.copyOf(config.globalContext());
            for (Path directory : config.pluginDirectories()) {
                Files.createDirectories(directory);
                try (var files = Files.list(directory)) {
                    for (Path archive : files.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList()) loadPlugin(archive);
                }
            }
            startPlugins().toCompletableFuture().join();
            for (KuudraConfig.FlowConfig flow : config.flows().values()) registerFlow(compile(flow));
            for (KuudraConfig.FlowConfig flow : config.flows().values()) {
                for (KuudraConfig.SourceBinding source : flow.sources()) installEventSource(source.component(), flow.id(), source.targetNodeId()).toCompletableFuture().join();
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Failed to apply Kuudra configuration", error);
        }
    }

    private void releaseResources() {
        if (plugins != null) try { plugins.close(); } catch (RuntimeException ignored) { }
        for (PluginArchiveLoader.LoadedArchive archive : archives) try { archive.close(); } catch (IOException ignored) { }
        archives.clear();
        if (runtimeEvents != null) try { runtimeEvents.close(); } catch (Exception ignored) { }
        if (runtime != null) try { runtime.close(); } catch (RuntimeException ignored) { }
        runtime = null;
        plugins = null;
        runtimeEvents = null;
        globalContext = Map.of();
    }

    private KuudraFlow compile(KuudraConfig.FlowConfig definition) {
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        for (KuudraConfig.NodeConfig node : definition.nodes().values()) {
            FlowNode compiled = switch (node.type()) {
                case "event-adapter" -> new FlowNode.AdapterNode(node.id(), pluginComponents().create(node.component(), EventAdapter.class));
                case "event-processor" -> new FlowNode.ProcessorNode(node.id(), pluginComponents().create(node.component(), EventProcessor.class));
                case "actor" -> new FlowNode.ActorNode(node.id(), pluginComponents().create(node.component(), Actor.class));
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
        String admissionKey = text(options.getOrDefault("admissionKey", "default"));
        SessionPolicy policy = SessionPolicy.valueOf(text(options.getOrDefault("policy", SessionPolicy.PARALLEL.name())));
        ParentTerminationPolicy parent = ParentTerminationPolicy.valueOf(text(options.getOrDefault("parentTerminationPolicy", ParentTerminationPolicy.NONE.name())));
        return new SessionSpec(name, admissionKey, policy, parent);
    }
    private static String text(Object value) { if (value == null) throw new IllegalArgumentException("Configuration value must not be null"); return value.toString(); }
    public record Flow(String id, String status, int activeSessions, int deferredTasks) { }
    public record Session(UUID id, String flowId, String name, String admissionKey, String status, boolean cancellationRequested, java.util.Set<UUID> parentSessionIds) { }
}
