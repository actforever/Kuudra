package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.ActionContext;
import io.github.actforever.kuudra.api.CancellationToken;
import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.RawSignalProcessor;
import io.github.actforever.kuudra.api.RuntimeStateView;
import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSnapshot;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.api.SessionStatus;
import io.github.actforever.kuudra.api.Signal;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * A single-process, in-memory Kuudra runtime.
 * Raw signals are processed by a bounded task queue; admitted session signals run Actors serially.
 */
public final class KuudraRuntime implements AutoCloseable, RuntimeStateView {
    private final Object monitor = new Object();
    private final Map<String, KuudraFlow> flows = new java.util.HashMap<>();
    private final Map<String, IngressPipeline> pipelines = new java.util.HashMap<>();
    private final Map<UUID, ManagedSession> sessions = new java.util.HashMap<>();
    private final Map<GroupKey, ArrayDeque<PendingAdmission>> waiting = new java.util.HashMap<>();
    private final LinkedBlockingQueue<RawTask> queue;
    private final ExecutorService dispatcher;
    private final ExecutorService actorPool;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KuudraRuntime(int queueCapacity, int actorThreads) {
        if (queueCapacity < 1 || actorThreads < 1) throw new IllegalArgumentException("capacities must be positive");
        queue = new LinkedBlockingQueue<>(queueCapacity);
        dispatcher = Executors.newSingleThreadExecutor(r -> new Thread(r, "kuudra-dispatcher"));
        actorPool = Executors.newFixedThreadPool(actorThreads, r -> new Thread(r, "kuudra-actor"));
        dispatcher.execute(this::dispatchLoop);
    }

    public void registerFlow(KuudraFlow flow) {
        synchronized (monitor) {
            if (flows.putIfAbsent(flow.id(), flow) != null) throw new IllegalArgumentException("duplicate flow: " + flow.id());
        }
    }

    public void registerIngress(IngressPipeline pipeline) {
        synchronized (monitor) {
            if (pipelines.putIfAbsent(pipeline.id(), pipeline) != null) throw new IllegalArgumentException("duplicate ingress: " + pipeline.id());
        }
    }

    /** Non-blocking source entry point. false means the bounded runtime queue rejected the signal. */
    public boolean publish(String pipelineId, RawSignal signal) {
        if (!running.get()) return false;
        synchronized (monitor) {
            if (!pipelines.containsKey(pipelineId)) throw new IllegalArgumentException("unknown ingress: " + pipelineId);
        }
        return queue.offer(new RawTask(pipelineId, signal));
    }

    public boolean cancel(UUID sessionId) {
        synchronized (monitor) {
            ManagedSession session = sessions.get(sessionId);
            if (session == null || !session.isActive()) return false;
            session.cancelled.set(true);
            session.status = SessionStatus.CANCELLATION_REQUESTED;
            return true;
        }
    }

    @Override
    public boolean hasActiveSession(String flowId, String sessionName) {
        return activeSessionCount(flowId, sessionName) > 0;
    }

    @Override
    public int activeSessionCount(String flowId, String sessionName) {
        synchronized (monitor) {
            return (int) sessions.values().stream()
                    .filter(s -> s.isActive() && s.flow.id().equals(flowId) && s.spec.name().equals(sessionName))
                    .count();
        }
    }

    @Override
    public Optional<SessionSnapshot> session(UUID sessionId) {
        synchronized (monitor) {
            ManagedSession s = sessions.get(sessionId);
            return s == null ? Optional.empty() : Optional.of(s.snapshot());
        }
    }

    public boolean awaitNoActiveSessions(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (sessions.values().stream().anyMatch(ManagedSession::isActive)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
            return true;
        }
    }

    private void dispatchLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                RawTask task = queue.poll(100, TimeUnit.MILLISECONDS);
                if (task != null) dispatch(task);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException error) {
                System.err.println("Kuudra raw pipeline error: " + error.getMessage());
            }
        }
    }

    private void dispatch(RawTask task) {
        IngressPipeline pipeline;
        synchronized (monitor) { pipeline = pipelines.get(task.pipelineId); }
        if (pipeline == null) return;
        List<RawSignal> current = List.of(task.signal);
        for (RawSignalProcessor processor : pipeline.processors()) {
            List<RawSignal> next = new ArrayList<>();
            for (RawSignal raw : current) next.addAll(processor.process(raw));
            current = next;
            if (current.isEmpty()) return;
        }
        for (RawSignal raw : current) {
            for (IngressPipeline.Output output : pipeline.outputs()) {
                if (output.selector().test(raw)) admit(output.flowId(), raw);
            }
        }
    }

    private void admit(String flowId, RawSignal raw) {
        ManagedSession created = null;
        synchronized (monitor) {
            KuudraFlow flow = flows.get(flowId);
            if (flow == null) throw new IllegalStateException("ingress points to unknown flow: " + flowId);
            Optional<SessionSpec> optionalSpec = flow.sessionProcessor().process(raw, this);
            if (optionalSpec.isEmpty()) return;
            SessionSpec spec = optionalSpec.get();
            GroupKey key = new GroupKey(flowId, spec.name(), spec.admissionKey());
            boolean groupActive = sessions.values().stream().anyMatch(s -> s.isActive() && s.groupKey.equals(key));
            if (spec.policy() == SessionPolicy.IGNORE && groupActive) return;
            if (spec.policy() == SessionPolicy.TOGGLE && groupActive) {
                sessions.values().stream().filter(s -> s.isActive() && s.groupKey.equals(key)).forEach(s -> {
                    s.cancelled.set(true);
                    s.status = SessionStatus.CANCELLATION_REQUESTED;
                });
                return;
            }
            if (spec.policy() == SessionPolicy.QUEUED && groupActive) {
                waiting.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(new PendingAdmission(flow, raw, spec));
                return;
            }
            created = createSession(flow, raw, spec, key);
        }
        execute(created);
    }

    private ManagedSession createSession(KuudraFlow flow, RawSignal raw, SessionSpec spec, GroupKey key) {
        ManagedSession session = new ManagedSession(UUID.randomUUID(), flow, raw, spec, key);
        sessions.put(session.id, session);
        return session;
    }

    private void execute(ManagedSession session) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        long[] sequence = {0};
        for (var actor : session.flow.actors()) {
            chain = chain.thenComposeAsync(ignored -> actor.act(
                    new Signal(session.raw, session.id, session.flow.id(), sequence[0]++),
                    new ActionContext(session.id, session.flow.id(), session.cancelled::get)
            ).toCompletableFuture(), actorPool);
        }
        chain.whenComplete((ignored, error) -> complete(session, error));
    }

    private void complete(ManagedSession session, Throwable error) {
        ManagedSession queued = null;
        synchronized (monitor) {
            if (error != null) session.status = SessionStatus.FAILED;
            else session.status = session.cancelled.get() ? SessionStatus.CANCELLED : SessionStatus.COMPLETED;
            ArrayDeque<PendingAdmission> pending = waiting.get(session.groupKey);
            if (pending != null && !pending.isEmpty() && sessions.values().stream().noneMatch(s -> s != session && s.isActive() && s.groupKey.equals(session.groupKey))) {
                PendingAdmission next = pending.removeFirst();
                queued = createSession(next.flow, next.raw, next.spec, session.groupKey);
                if (pending.isEmpty()) waiting.remove(session.groupKey);
            }
            monitor.notifyAll();
        }
        if (queued != null) execute(queued);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        dispatcher.shutdownNow();
        actorPool.shutdownNow();
    }

    private record RawTask(String pipelineId, RawSignal signal) { }
    private record GroupKey(String flowId, String name, String admissionKey) { }
    private record PendingAdmission(KuudraFlow flow, RawSignal raw, SessionSpec spec) { }

    private static final class ManagedSession {
        private final UUID id;
        private final KuudraFlow flow;
        private final RawSignal raw;
        private final SessionSpec spec;
        private final GroupKey groupKey;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private SessionStatus status = SessionStatus.ACTIVE;

        private ManagedSession(UUID id, KuudraFlow flow, RawSignal raw, SessionSpec spec, GroupKey groupKey) {
            this.id = id; this.flow = flow; this.raw = raw; this.spec = spec; this.groupKey = groupKey;
        }

        private boolean isActive() {
            return status == SessionStatus.ACTIVE || status == SessionStatus.CANCELLATION_REQUESTED;
        }

        private SessionSnapshot snapshot() {
            return new SessionSnapshot(id, flow.id(), spec.name(), spec.admissionKey(), status, cancelled.get());
        }
    }
}
