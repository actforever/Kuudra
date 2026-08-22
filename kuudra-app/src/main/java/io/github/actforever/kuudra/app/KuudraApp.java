package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.FlowSnapshot;
import io.github.actforever.kuudra.api.SessionSnapshot;
import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.api.SourceRegistration;
import io.github.actforever.kuudra.plugin.DefaultPluginManager;
import io.github.actforever.kuudra.plugin.PluginArchiveLoader;
import io.github.actforever.kuudra.plugin.PluginComponentRegistry;
import io.github.actforever.kuudra.runtime.KuudraRuntime;
import io.github.actforever.kuudra.runtime.KuudraFlow;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;

/**
 * Framework-independent application facade that composes the Kuudra core.
 * Adapters such as kuudra-web interact with this type, never with Runtime.
 */
public final class KuudraApp implements AutoCloseable {
    private final KuudraRuntime runtime;
    private final DefaultPluginManager plugins;
    private final List<PluginArchiveLoader.LoadedArchive> archives = new ArrayList<>();

    public KuudraApp(int queueCapacity, int workerThreads) {
        KuudraBanner.print();
        runtime = new KuudraRuntime(queueCapacity, workerThreads);
        plugins = new DefaultPluginManager(Path.of("plugins", "homes"), runtime::registerSource);
    }

    public static KuudraApp createDefault() {
        return new KuudraApp(1_024, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    }

    public Health health() {
        return new Health("UP", runtime.queuedTasks(), runtime.flows().size());
    }

    public List<Flow> flows() {
        return runtime.flows().stream().map(KuudraApp::flow).toList();
    }

    public Optional<Flow> flow(String flowId) {
        return runtime.flow(flowId).map(KuudraApp::flow);
    }

    public void activateFlow(String flowId) { runtime.activateFlow(flowId); }
    public void pauseFlow(String flowId) { runtime.pauseFlow(flowId); }
    public void resumeFlow(String flowId) { runtime.resumeFlow(flowId); }
    public void stopFlow(String flowId) { runtime.stopFlow(flowId); }

    public Optional<Session> session(UUID sessionId) {
        return runtime.session(sessionId).map(KuudraApp::session);
    }

    public boolean cancelSession(UUID sessionId) {
        return runtime.cancel(sessionId);
    }

    /** Application assembly API used by configuration compilers, not by transport adapters. */
    public void registerFlow(KuudraFlow flow) { runtime.registerFlow(flow); }
    public boolean publish(String flowId, String targetNodeId, Event event) { return runtime.publish(flowId, targetNodeId, event); }
    public boolean awaitNoActiveSessions(Duration timeout) throws InterruptedException { return runtime.awaitNoActiveSessions(timeout); }

    /** Loads and validates plugin metadata, dependencies and annotated component definitions. */
    public synchronized void loadPlugin(Path archive) throws IOException {
        PluginArchiveLoader.LoadedArchive loaded = new PluginArchiveLoader().load(archive, KuudraApp.class.getClassLoader());
        try {
            plugins.register(loaded.plugin());
            archives.add(loaded);
        } catch (RuntimeException error) {
            try { loaded.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    public java.util.concurrent.CompletionStage<Void> startPlugins() { return plugins.startAll(); }
    public PluginComponentRegistry pluginComponents() { return plugins.components(); }

    /**
     * Configuration assembly hook: resolves a plugin declaration such as
     * {@code event-source/hello-world/loop-emitter}, constructs it, then attaches it to a Flow node.
     */
    public java.util.concurrent.CompletionStage<SourceRegistration> installEventSource(String componentReference, String flowId, String targetNodeId) {
        EventSource source = plugins.components().create(componentReference, EventSource.class);
        return runtime.registerSource(flowId, targetNodeId, source);
    }

    @Override
    public synchronized void close() {
        try { plugins.close(); }
        finally {
            for (PluginArchiveLoader.LoadedArchive archive : archives) try { archive.close(); } catch (IOException ignored) { }
            archives.clear();
            runtime.close();
        }
    }

    private static Flow flow(FlowSnapshot snapshot) {
        return new Flow(snapshot.flowId(), snapshot.status().name(), snapshot.activeSessions(), snapshot.deferredTasks());
    }

    private static Session session(SessionSnapshot snapshot) {
        return new Session(snapshot.id(), snapshot.flowId(), snapshot.name(), snapshot.admissionKey(),
                snapshot.status().name(), snapshot.cancellationRequested());
    }

    public record Health(String status, int queuedTasks, int flows) { }
    public record Flow(String id, String status, int activeSessions, int deferredTasks) { }
    public record Session(UUID id, String flowId, String name, String admissionKey, String status,
                          boolean cancellationRequested) { }
}
