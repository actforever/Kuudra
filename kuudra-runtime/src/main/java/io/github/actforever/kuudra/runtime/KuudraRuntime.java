package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.RawSignalProcessor;
import io.github.actforever.kuudra.api.RawSignalSource;
import io.github.actforever.kuudra.api.RootSignal;
import io.github.actforever.kuudra.api.RootSignalSource;
import io.github.actforever.kuudra.api.SourceRegistration;
import io.github.actforever.kuudra.api.FlowSnapshot;
import io.github.actforever.kuudra.api.FlowStatus;
import io.github.actforever.kuudra.api.RuntimeStateView;
import io.github.actforever.kuudra.api.SessionContext;
import io.github.actforever.kuudra.api.SessionProcessorContext;
import io.github.actforever.kuudra.api.SessionSnapshot;
import io.github.actforever.kuudra.api.SessionStatus;
import io.github.actforever.kuudra.api.Signal;
import io.github.actforever.kuudra.api.SignalContext;
import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;
import io.github.actforever.kuudra.logging.KuudraLog;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Single-process queue-driven runtime for all three signal stages. */
public final class KuudraRuntime implements AutoCloseable, RuntimeStateView {
    private static final org.slf4j.Logger LOG = KuudraLog.getLogger(KuudraRuntime.class);
    private final Object monitor = new Object();
    private final Map<String, RegisteredFlow> flows = new HashMap<>();
    private final Map<String, IngressPipeline> pipelines = new HashMap<>();
    private final Map<UUID, ManagedSession> sessions = new HashMap<>();
    private final Map<GroupKey, ArrayDeque<RootSignal>> queuedRoots = new HashMap<>();
    private final CopyOnWriteArrayList<SourceRegistration> sourceRegistrations = new CopyOnWriteArrayList<>();
    private final KuudraTaskQueue queue;
    private final ExecutorService dispatcher;
    private final ExecutorService workers;
    private final SimpleSystemEventBus events = new SimpleSystemEventBus();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KuudraRuntime(int queueCapacity, int workerThreads) {
        this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads);
    }

    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads) {
        if (workerThreads < 1) throw new IllegalArgumentException("workerThreads must be positive");
        this.queue = queue;
        dispatcher = Executors.newSingleThreadExecutor(r -> new Thread(r, "kuudra-dispatcher"));
        workers = Executors.newFixedThreadPool(workerThreads, r -> new Thread(r, "kuudra-worker"));
        dispatcher.execute(this::dispatchLoop);
        LOG.info("KuudraRuntime started with {} worker(s)", workerThreads);
    }

    public SystemEventBus systemEvents() { return events; }
    public int queuedTasks() { return queue.size(); }

    public void registerFlow(KuudraFlow flow) {
        synchronized (monitor) {
            if (flows.putIfAbsent(flow.id(), new RegisteredFlow(flow)) != null) throw new IllegalArgumentException("duplicate flow: " + flow.id());
        }
        event("flow.registered", Map.of("flowId", flow.id()));
    }

    public void activateFlow(String flowId) {
        synchronized (monitor) { requireFlow(flowId).status = FlowStatus.ACTIVE; }
        event("flow.active", Map.of("flowId", flowId));
    }

    /** Stop accepting roots but retain session continuations until explicitly resumed or stopped. */
    public void pauseFlow(String flowId) {
        synchronized (monitor) {
            RegisteredFlow flow = requireFlow(flowId);
            if (flow.status == FlowStatus.ACTIVE) flow.status = FlowStatus.PAUSED;
        }
        event("flow.paused", Map.of("flowId", flowId));
    }

    /** Re-enqueue continuations held while the Flow was paused. */
    public void resumeFlow(String flowId) {
        List<RuntimeTask.SignalTask> deferred;
        synchronized (monitor) {
            RegisteredFlow flow = requireFlow(flowId);
            if (flow.status != FlowStatus.PAUSED) return;
            flow.status = FlowStatus.ACTIVE;
            deferred = List.copyOf(flow.deferred);
            flow.deferred.clear();
        }
        deferred.forEach(task -> {
            if (!offer(task)) {
                synchronized (monitor) { ManagedSession session = sessions.get(task.sessionId()); if (session != null) release(session, null); }
            }
        });
        event("flow.resumed", Map.of("flowId", flowId, "continuations", deferred.size()));
    }

    /** Close the root admission gate and cooperatively cancel current sessions. */
    public void stopFlow(String flowId) {
        List<UUID> toCancel = new ArrayList<>();
        List<RuntimeTask.SignalTask> deferred;
        synchronized (monitor) {
            RegisteredFlow flow = requireFlow(flowId);
            flow.status = FlowStatus.STOPPING;
            deferred = List.copyOf(flow.deferred);
            flow.deferred.clear();
            sessions.values().stream().filter(s -> s.flow.id().equals(flowId) && s.isActive()).forEach(s -> toCancel.add(s.id));
        }
        toCancel.forEach(this::cancel);
        deferred.forEach(task -> {
            if (!offer(task)) {
                synchronized (monitor) {
                    ManagedSession session = sessions.get(task.sessionId());
                    if (session != null) release(session, null);
                }
            }
        });
        event("flow.stopping", Map.of("flowId", flowId));
    }

    public void registerIngress(IngressPipeline pipeline) {
        synchronized (monitor) {
            if (pipelines.putIfAbsent(pipeline.id(), pipeline) != null) throw new IllegalArgumentException("duplicate ingress: " + pipeline.id());
        }
    }

    public CompletionStage<SourceRegistration> registerSource(String pipelineId, RawSignalSource source) {
        synchronized (monitor) {
            if (!pipelines.containsKey(pipelineId)) throw new IllegalArgumentException("unknown ingress: " + pipelineId);
        }
        source.setEmitter(raw -> publishRaw(pipelineId, raw));
        return source.start().thenApply(ignored -> registerSourceStop(source::stop));
    }

    public CompletionStage<SourceRegistration> registerRootSource(RootSignalSource source) {
        source.setEmitter(this::publishRoot);
        return source.start().thenApply(ignored -> registerSourceStop(source::stop));
    }

    private SourceRegistration registerSourceStop(java.util.function.Supplier<CompletionStage<Void>> stop) {
        AtomicBoolean removed = new AtomicBoolean();
        AtomicReference<SourceRegistration> reference = new AtomicReference<>();
        SourceRegistration registration = () -> {
            if (!removed.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
            sourceRegistrations.remove(reference.get());
            return stop.get();
        };
        reference.set(registration);
        sourceRegistrations.add(registration);
        return registration;
    }

    public boolean publishRaw(String pipelineId, RawSignal signal) {
        synchronized (monitor) {
            if (!pipelines.containsKey(pipelineId)) throw new IllegalArgumentException("unknown ingress: " + pipelineId);
        }
        return offer(new RuntimeTask.RawTask(pipelineId, signal));
    }

    public boolean publishRoot(RootSignal signal) { return offer(new RuntimeTask.RootTask(signal)); }

    public boolean cancel(UUID sessionId) {
        synchronized (monitor) {
            ManagedSession session = sessions.get(sessionId);
            if (session == null || !session.isActive()) return false;
            session.cancelled.set(true);
            session.status = SessionStatus.CANCELLATION_REQUESTED;
        }
        event("session.cancel.requested", Map.of("sessionId", sessionId.toString()));
        return true;
    }

    @Override public boolean hasActiveSession(String flowId, String sessionName) { return activeSessionCount(flowId, sessionName) > 0; }
    @Override public int activeSessionCount(String flowId, String sessionName) {
        synchronized (monitor) {
            return (int) sessions.values().stream().filter(s -> s.isActive() && s.flow.id().equals(flowId)
                    && s.root.sessionSpec().name().equals(sessionName)).count();
        }
    }
    @Override public Optional<SessionSnapshot> session(UUID sessionId) {
        synchronized (monitor) { return Optional.ofNullable(sessions.get(sessionId)).map(ManagedSession::snapshot); }
    }
    @Override public Optional<FlowSnapshot> flow(String flowId) {
        synchronized (monitor) {
            RegisteredFlow flow = flows.get(flowId);
            if (flow == null) return Optional.empty();
            int active = (int) sessions.values().stream().filter(s -> s.isActive() && s.flow.id().equals(flowId)).count();
            return Optional.of(new FlowSnapshot(flowId, flow.status, active, flow.deferred.size()));
        }
    }
    /** Immutable diagnostic snapshot of all currently registered Flows. */
    public List<FlowSnapshot> flows() {
        synchronized (monitor) {
            return flows.keySet().stream().map(this::flow).flatMap(Optional::stream).toList();
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

    private boolean offer(RuntimeTask task) {
        boolean accepted = running.get() && queue.offer(task);
        if (!accepted) event("queue.rejected", Map.of("task", task.getClass().getSimpleName()));
        return accepted;
    }

    private void dispatchLoop() {
        while (running.get() || queue.size() > 0) {
            try { queue.poll(Duration.ofMillis(100)).ifPresent(this::dispatch); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            catch (RuntimeException error) { event("runtime.dispatch.failed", Map.of("error", error.toString())); }
        }
    }

    private void dispatch(RuntimeTask task) {
        if (task instanceof RuntimeTask.RawTask raw) dispatchRaw(raw);
        else if (task instanceof RuntimeTask.RootTask root) admit(root.signal());
        else if (task instanceof RuntimeTask.SignalTask signal) dispatchSignal(signal);
    }

    private void dispatchRaw(RuntimeTask.RawTask task) {
        IngressPipeline pipeline;
        synchronized (monitor) { pipeline = pipelines.get(task.pipelineId()); }
        if (pipeline == null) return;
        List<RawSignal> signals = List.of(task.signal());
        for (RawSignalProcessor processor : pipeline.processors()) {
            List<RawSignal> next = new ArrayList<>();
            for (RawSignal raw : signals) next.addAll(processor.process(raw));
            signals = List.copyOf(next);
            if (signals.isEmpty()) return;
        }
        for (RawSignal raw : signals) for (IngressPipeline.Output output : pipeline.outputs()) {
            if (!output.selector().test(raw)) continue;
            RegisteredFlow flow;
            synchronized (monitor) { flow = requireFlow(output.flowId()); }
            if (flow.status != FlowStatus.ACTIVE) continue;
            List<RootSignal> roots = flow.flow.sessionProcessor().process(raw, new SessionProcessorContext(flow.flow.id(), this));
            roots.forEach(this::publishRoot);
        }
    }

    private void admit(RootSignal root) {
        ManagedSession created = null;
        synchronized (monitor) {
            RegisteredFlow registration = requireFlow(root.flowId());
            if (registration.status != FlowStatus.ACTIVE) { event("root.rejected.inactiveFlow", Map.of("flowId", root.flowId())); return; }
            GroupKey key = new GroupKey(root.flowId(), root.sessionSpec().name(), root.sessionSpec().admissionKey());
            boolean active = sessions.values().stream().anyMatch(s -> s.isActive() && s.group.equals(key));
            switch (root.sessionSpec().policy()) {
                case IGNORE -> { if (active) return; }
                case TOGGLE -> {
                    if (active) {
                        sessions.values().stream().filter(s -> s.isActive() && s.group.equals(key)).forEach(s -> { s.cancelled.set(true); s.status = SessionStatus.CANCELLATION_REQUESTED; });
                        return;
                    }
                }
                case QUEUED -> {
                    if (active) { queuedRoots.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(root); return; }
                }
                case PARALLEL -> { }
            }
            created = createSession(registration.flow, root, key);
        }
        enqueueEntry(created);
    }

    private ManagedSession createSession(KuudraFlow flow, RootSignal root, GroupKey group) {
        ManagedSession session = new ManagedSession(UUID.randomUUID(), flow, root, group, workers);
        sessions.put(session.id, session);
        event("session.active", Map.of("sessionId", session.id.toString(), "flowId", flow.id()));
        return session;
    }

    private void enqueueEntry(ManagedSession session) {
        Signal signal = new Signal(session.root.raw(), session.id, session.flow.id(), session.nextSequence());
        enqueueSignal(session, session.flow.entryNodeId(), signal);
    }

    private void enqueueSignal(ManagedSession session, String nodeId, Signal signal) {
        if (!session.flow.id().equals(signal.flowId()) || !session.id.equals(signal.sessionId())) {
            fail(session, new IllegalArgumentException("a Flow node cannot emit a signal for another Flow/session")); return;
        }
        session.work.incrementAndGet();
        RuntimeTask.SignalTask task = new RuntimeTask.SignalTask(session.id, nodeId, signal);
        synchronized (monitor) {
            RegisteredFlow flow = requireFlow(session.flow.id());
            if (flow.status == FlowStatus.PAUSED) {
                flow.deferred.add(task);
                return;
            }
        }
        if (!offer(task)) release(session, null);
    }

    private void dispatchSignal(RuntimeTask.SignalTask task) {
        ManagedSession session;
        synchronized (monitor) { session = sessions.get(task.sessionId()); }
        if (session == null || !session.isActive()) return;
        synchronized (monitor) {
            RegisteredFlow flow = requireFlow(session.flow.id());
            if (flow.status == FlowStatus.PAUSED) { flow.deferred.add(task); return; }
        }
        session.submit(() -> {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            FlowNode node = session.flow.nodes().get(task.nodeId());
            if (node == null) {
                IllegalArgumentException error = new IllegalArgumentException("unknown flow node: " + task.nodeId());
                release(session, error);
                completion.completeExceptionally(error);
                return completion;
            }
            SignalContext context = new SignalContext(session.id, session.flow.id(), session.context.snapshot(), session.context, session.cancelled::get);
            try {
                node.apply(task.signal(), context).whenComplete((out, error) -> {
                    if (error == null) {
                        for (Signal emitted : out) for (String next : session.flow.next(task.nodeId())) enqueueSignal(session, next, emitted);
                    }
                    release(session, error);
                    if (error == null) completion.complete(null); else completion.completeExceptionally(error);
                });
            } catch (RuntimeException error) { release(session, error); completion.completeExceptionally(error); }
            return completion;
        });
    }

    private void release(ManagedSession session, Throwable error) {
        if (error != null) { fail(session, error); return; }
        if (session.work.decrementAndGet() != 0) return;
        complete(session);
    }

    private void fail(ManagedSession session, Throwable error) {
        if (!session.terminal.compareAndSet(false, true)) return;
        synchronized (monitor) { session.status = SessionStatus.FAILED; monitor.notifyAll(); }
        event("session.failed", Map.of("sessionId", session.id.toString(), "error", error.toString()));
        admitNext(session.group);
        markStoppedIfDrained(session.flow.id());
    }

    private void complete(ManagedSession session) {
        synchronized (monitor) {
            if (!session.isActive()) return;
            session.status = session.cancelled.get() ? SessionStatus.CANCELLED : SessionStatus.COMPLETED;
            monitor.notifyAll();
        }
        event("session." + session.status.name().toLowerCase(), Map.of("sessionId", session.id.toString()));
        admitNext(session.group);
        markStoppedIfDrained(session.flow.id());
    }

    private void admitNext(GroupKey group) {
        RootSignal next = null;
        synchronized (monitor) {
            ArrayDeque<RootSignal> pending = queuedRoots.get(group);
            if (pending != null && !pending.isEmpty() && sessions.values().stream().noneMatch(s -> s.isActive() && s.group.equals(group))) {
                next = pending.removeFirst();
                if (pending.isEmpty()) queuedRoots.remove(group);
            }
        }
        if (next != null) publishRoot(next);
    }

    private void markStoppedIfDrained(String flowId) {
        boolean stopped = false;
        synchronized (monitor) {
            RegisteredFlow flow = requireFlow(flowId);
            if (flow.status == FlowStatus.STOPPING && sessions.values().stream().noneMatch(s -> s.isActive() && s.flow.id().equals(flowId)) && flow.deferred.isEmpty()) {
                flow.status = FlowStatus.STOPPED;
                stopped = true;
            }
        }
        if (stopped) event("flow.stopped", Map.of("flowId", flowId));
    }

    private RegisteredFlow requireFlow(String id) {
        RegisteredFlow flow = flows.get(id);
        if (flow == null) throw new IllegalArgumentException("unknown flow: " + id);
        return flow;
    }
    private void event(String type, Map<String, Object> data) { events.publish(SystemEvent.of(type, data)); }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        for (SourceRegistration registration : sourceRegistrations) {
            try { registration.unregister().toCompletableFuture().join(); } catch (RuntimeException ignored) { }
        }
        synchronized (monitor) { sessions.values().stream().filter(ManagedSession::isActive).forEach(s -> { s.cancelled.set(true); s.status = SessionStatus.CANCELLATION_REQUESTED; }); }
        queue.close(); dispatcher.shutdownNow(); workers.shutdownNow();
        LOG.info("KuudraRuntime stopped");
    }

    private record GroupKey(String flowId, String name, String admissionKey) { }
    private static final class RegisteredFlow {
        private final KuudraFlow flow;
        private FlowStatus status = FlowStatus.ACTIVE;
        private final List<RuntimeTask.SignalTask> deferred = new ArrayList<>();
        private RegisteredFlow(KuudraFlow flow) { this.flow = flow; }
    }

    private static final class ManagedSession {
        private final UUID id; private final KuudraFlow flow; private final RootSignal root; private final GroupKey group;
        private final AtomicBoolean cancelled = new AtomicBoolean(); private final AtomicBoolean terminal = new AtomicBoolean(); private final AtomicInteger work = new AtomicInteger();
        private final AtomicSessionContext context = new AtomicSessionContext();
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        private long sequence; private volatile SessionStatus status = SessionStatus.ACTIVE;
        private ManagedSession(UUID id, KuudraFlow flow, RootSignal root, GroupKey group, Executor executor) {
            this.id = id; this.flow = flow; this.root = root; this.group = group; this.executor = executor;
        }
        private final Executor executor;
        private synchronized long nextSequence() { return sequence++; }
        private synchronized void submit(java.util.function.Supplier<CompletionStage<Void>> operation) {
            tail = tail.handle((ignored, error) -> null).thenComposeAsync(ignored -> operation.get().toCompletableFuture(), executor);
        }
        private boolean isActive() { return status == SessionStatus.ACTIVE || status == SessionStatus.CANCELLATION_REQUESTED; }
        private SessionSnapshot snapshot() { return new SessionSnapshot(id, flow.id(), root.sessionSpec().name(), root.sessionSpec().admissionKey(), status, cancelled.get()); }
    }

    private static final class AtomicSessionContext implements SessionContext {
        private final AtomicReference<Map<String, Object>> values = new AtomicReference<>(Map.of());
        @Override public Map<String, Object> snapshot() { return values.get(); }
        @Override public boolean compareAndSet(Map<String, Object> expected, Map<String, Object> replacement) { return values.compareAndSet(expected, Map.copyOf(replacement)); }
        @Override public Map<String, Object> update(java.util.function.UnaryOperator<Map<String, Object>> operation) {
            while (true) { Map<String, Object> current = values.get(); Map<String, Object> next = Map.copyOf(operation.apply(current)); if (values.compareAndSet(current, next)) return next; }
        }
    }
}
