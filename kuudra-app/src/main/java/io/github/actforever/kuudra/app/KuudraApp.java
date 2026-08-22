package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.FlowSnapshot;
import io.github.actforever.kuudra.api.SessionSnapshot;
import io.github.actforever.kuudra.runtime.KuudraRuntime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Framework-independent application facade that composes the Kuudra core.
 * Adapters such as kuudra-web interact with this type, never with Runtime.
 */
public final class KuudraApp implements AutoCloseable {
    private final KuudraRuntime runtime;

    public KuudraApp(int queueCapacity, int workerThreads) {
        KuudraBanner.print();
        runtime = new KuudraRuntime(queueCapacity, workerThreads);
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

    @Override public void close() { runtime.close(); }

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
