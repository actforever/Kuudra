package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.AppLifecycle;
import io.github.actforever.kuudra.api.AppSnapshot;
import io.github.actforever.kuudra.api.AppStatus;
import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.api.FlowSnapshot;
import io.github.actforever.kuudra.api.SessionSnapshot;
import io.github.actforever.kuudra.api.SourceRegistration;
import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;
import io.github.actforever.kuudra.plugin.DefaultPluginManager;
import io.github.actforever.kuudra.plugin.PluginArchiveLoader;
import io.github.actforever.kuudra.plugin.PluginComponentRegistry;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;
import io.github.actforever.kuudra.runtime.SimpleSystemEventBus;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Framework-independent App facade. Its lifecycle owns a Runtime but not any HTTP/Web/TUI adapter. */
public final class KuudraApp implements AutoCloseable, AppLifecycle {
    private final int queueCapacity;
    private final int workerThreads;
    private final SystemEventBus events = new SimpleSystemEventBus();
    private final List<PluginArchiveLoader.LoadedArchive> archives = new ArrayList<>();
    private KuudraRuntime runtime;
    private DefaultPluginManager plugins;
    private AutoCloseable runtimeEvents;
    private AppStatus status = AppStatus.NEW;
    private String detail = "not started";

    public KuudraApp(int queueCapacity, int workerThreads) { this.queueCapacity = queueCapacity; this.workerThreads = workerThreads; start(); }
    public static KuudraApp createDefault() { return new KuudraApp(1_024, Math.max(2, Runtime.getRuntime().availableProcessors() / 2)); }

    @Override public synchronized void start() {
        if (status == AppStatus.RUNNING || status == AppStatus.STARTING) return;
        status = AppStatus.STARTING; publish("app.starting");
        try {
            KuudraBanner.print();
            runtime = new KuudraRuntime(queueCapacity, workerThreads);
            runtimeEvents = runtime.systemEvents().subscribe(events::publish);
            plugins = new DefaultPluginManager(Path.of("plugins", "homes"), runtime::registerSource);
            status = AppStatus.RUNNING; detail = ""; publish("app.running");
        } catch (RuntimeException failure) {
            status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed"); throw failure;
        }
    }

    @Override public synchronized void stop() {
        if (status == AppStatus.STOPPED || status == AppStatus.NEW) return;
        status = AppStatus.STOPPING; publish("app.stopping");
        try {
            if (plugins != null) plugins.close();
            for (PluginArchiveLoader.LoadedArchive archive : archives) try { archive.close(); } catch (IOException ignored) { }
            archives.clear();
            if (runtimeEvents != null) try { runtimeEvents.close(); } catch (Exception ignored) { }
            if (runtime != null) runtime.close();
            runtime = null; plugins = null; runtimeEvents = null; status = AppStatus.STOPPED; detail = ""; publish("app.stopped");
        } catch (RuntimeException failure) {
            status = AppStatus.FAILED; detail = failure.toString(); publish("app.failed"); throw failure;
        }
    }

    @Override public synchronized AppSnapshot snapshot() {
        return runtime == null ? new AppSnapshot(status, 0, 0, detail) : new AppSnapshot(status, runtime.queuedTasks(), runtime.flows().size(), detail);
    }
    public SystemEventBus systemEvents() { return events; }
    public Health health() { AppSnapshot snapshot = snapshot(); return new Health(snapshot.status().name(), snapshot.queuedTasks(), snapshot.flowCount()); }

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
    public record Flow(String id, String status, int activeSessions, int deferredTasks) { }
    public record Session(UUID id, String flowId, String name, String admissionKey, String status, boolean cancellationRequested, java.util.Set<UUID> parentSessionIds) { }
}
