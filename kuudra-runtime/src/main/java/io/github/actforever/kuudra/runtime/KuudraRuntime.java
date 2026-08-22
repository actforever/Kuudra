package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.EventContext;
import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.api.FlowSnapshot;
import io.github.actforever.kuudra.api.FlowStatus;
import io.github.actforever.kuudra.api.FlowContext;
import io.github.actforever.kuudra.api.GlobalContext;
import io.github.actforever.kuudra.api.ContextCodec;
import io.github.actforever.kuudra.api.ContextCodecs;
import io.github.actforever.kuudra.api.ValueContext;
import io.github.actforever.kuudra.api.ParentTerminationPolicy;
import io.github.actforever.kuudra.api.PlaceholderResolver;
import io.github.actforever.kuudra.api.RuntimeStateView;
import io.github.actforever.kuudra.api.SessionContext;
import io.github.actforever.kuudra.api.SessionReference;
import io.github.actforever.kuudra.api.SessionSnapshot;
import io.github.actforever.kuudra.api.SessionStatus;
import io.github.actforever.kuudra.api.SourceRegistration;
import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;
import io.github.actforever.kuudra.logging.KuudraLog;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

/** Single-queue Event runtime. Routing into an Allocator or Processor detaches a current Session. */
public final class KuudraRuntime implements AutoCloseable, RuntimeStateView {
    private static final int MAX_HOPS = 1_024;
    private static final org.slf4j.Logger LOG = KuudraLog.getLogger(KuudraRuntime.class);
    private final Object monitor = new Object();
    private final Map<String, RegisteredFlow> flows = new HashMap<>();
    private final Map<UUID, ManagedSession> sessions = new HashMap<>();
    private final Map<GroupKey, ArrayDeque<AllocationRequest>> queuedAllocations = new HashMap<>();
    private final Map<UUID, Set<UUID>> childrenByParent = new HashMap<>();
    private final CopyOnWriteArrayList<SourceRegistration> sourceRegistrations = new CopyOnWriteArrayList<>();
    private final KuudraTaskQueue queue;
    private final ExecutorService dispatcher;
    private final ExecutorService workers;
    private final SimpleSystemEventBus events = new SimpleSystemEventBus();
    private final AtomicGlobalContext globalContext;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KuudraRuntime(int queueCapacity, int workerThreads) { this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, Map.of()); }
    public KuudraRuntime(int queueCapacity, int workerThreads, Map<String, Object> globalContext) { this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, globalContext); }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads) { this(queue, workerThreads, Map.of()); }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads, Map<String, Object> globalContext) {
        if (workerThreads < 1) throw new IllegalArgumentException("workerThreads must be positive");
        this.queue = queue; this.globalContext = new AtomicGlobalContext(globalContext); dispatcher = Executors.newSingleThreadExecutor(r -> new Thread(r, "kuudra-dispatcher"));
        workers = Executors.newFixedThreadPool(workerThreads, r -> new Thread(r, "kuudra-worker")); dispatcher.execute(this::dispatchLoop);
        LOG.info("KuudraRuntime started with {} worker(s)", workerThreads);
    }

    public SystemEventBus systemEvents() { return events; }
    public GlobalContext globalContext() { return globalContext; }
    public FlowContext flowContext(String flowId) { synchronized (monitor) { return requireFlow(flowId).context; } }
    public int queuedTasks() { return queue.size(); }

    public void registerFlow(KuudraFlow flow) {
        synchronized (monitor) {
            if (flows.putIfAbsent(flow.id(), new RegisteredFlow(flow)) != null) throw new IllegalArgumentException("duplicate flow: " + flow.id());
        }
        event("flow.registered", Map.of("flowId", flow.id()));
    }
    public void activateFlow(String flowId) { setFlowStatus(flowId, FlowStatus.ACTIVE, "flow.active"); }
    /** PAUSED flows reject new delivery; they do not retain an unbounded in-memory backlog. */
    public void pauseFlow(String flowId) { setFlowStatus(flowId, FlowStatus.PAUSED, "flow.paused"); }
    public void resumeFlow(String flowId) { activateFlow(flowId); }
    public void stopFlow(String flowId) {
        List<UUID> active;
        synchronized (monitor) {
            RegisteredFlow flow = requireFlow(flowId); flow.status = FlowStatus.STOPPING;
            active = sessions.values().stream().filter(s -> s.flow.id().equals(flowId) && s.isActive()).map(s -> s.id).toList();
        }
        active.forEach(this::cancel); markStoppedIfDrained(flowId); event("flow.stopping", Map.of("flowId", flowId));
    }
    private void setFlowStatus(String flowId, FlowStatus status, String eventType) {
        synchronized (monitor) { requireFlow(flowId).status = status; }
        event(eventType, Map.of("flowId", flowId));
    }

    /** Binds a plugin EventSource to the first node of a Flow. Sources may only emit unbound Events. */
    public CompletionStage<SourceRegistration> registerSource(String flowId, String targetNodeId, EventSource source) {
        synchronized (monitor) { requireFlow(flowId).flow.node(targetNodeId); }
        source.setEmitter(event -> {
            if (event.hasSession()) throw new IllegalArgumentException("EventSource must emit an unbound Event");
            return publish(flowId, targetNodeId, event);
        });
        return source.start().thenApply(ignored -> registerSourceStop(source::stop));
    }
    private SourceRegistration registerSourceStop(java.util.function.Supplier<CompletionStage<Void>> stop) {
        AtomicBoolean removed = new AtomicBoolean(); AtomicReference<SourceRegistration> reference = new AtomicReference<>();
        SourceRegistration registration = () -> {
            if (!removed.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
            sourceRegistrations.remove(reference.get()); return stop.get();
        };
        reference.set(registration); sourceRegistrations.add(registration); return registration;
    }

    /** Publishes an Event to an explicit Flow node. The target edge determines session propagation. */
    public boolean publish(String flowId, String targetNodeId, Event event) {
        RegisteredFlow flow;
        synchronized (monitor) { flow = requireFlow(flowId); if (flow.status != FlowStatus.ACTIVE) return rejected("flow.inactive", flowId, targetNodeId); }
        return enqueue(flow.flow, targetNodeId, event);
    }
    private boolean rejected(String type, String flowId, String nodeId) {
        event(type, Map.of("flowId", flowId, "nodeId", nodeId)); return false;
    }

    public boolean cancel(UUID sessionId) { return requestCancellation(sessionId, "explicit"); }
    private boolean requestCancellation(UUID sessionId, String reason) {
        List<UUID> descendants = List.of();
        synchronized (monitor) {
            ManagedSession session = sessions.get(sessionId);
            if (session == null || !session.isActive() || !session.cancelled.compareAndSet(false, true)) return false;
            session.status = SessionStatus.CANCELLATION_REQUESTED;
            descendants = childSessions(sessionId, ParentTerminationPolicy.ON_PARENT_CANCELLATION);
        }
        event("session.cancel.requested", Map.of("sessionId", sessionId.toString(), "reason", reason));
        descendants.forEach(child -> requestCancellation(child, "parent-cancelled")); return true;
    }

    @Override public boolean hasActiveSession(String flowId, String sessionName) { return activeSessionCount(flowId, sessionName) > 0; }
    @Override public int activeSessionCount(String flowId, String sessionName) {
        synchronized (monitor) { return (int) sessions.values().stream().filter(s -> s.isActive() && s.flow.id().equals(flowId) && s.spec.name().equals(sessionName)).count(); }
    }
    @Override public Optional<SessionSnapshot> session(UUID sessionId) { synchronized (monitor) { return Optional.ofNullable(sessions.get(sessionId)).map(ManagedSession::snapshot); } }
    @Override public Optional<FlowSnapshot> flow(String flowId) {
        synchronized (monitor) {
            RegisteredFlow flow = flows.get(flowId); if (flow == null) return Optional.empty();
            int active = (int) sessions.values().stream().filter(s -> s.isActive() && s.flow.id().equals(flowId)).count();
            return Optional.of(new FlowSnapshot(flowId, flow.status, active, 0));
        }
    }
    public List<FlowSnapshot> flows() { synchronized (monitor) { return flows.keySet().stream().map(this::flow).flatMap(Optional::stream).toList(); } }
    public boolean awaitNoActiveSessions(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (sessions.values().stream().anyMatch(ManagedSession::isActive)) {
                long remaining = deadline - System.nanoTime(); if (remaining <= 0) return false; TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
            return true;
        }
    }

    private boolean enqueue(KuudraFlow flow, String targetNodeId, Event original) {
        if (original.lineage().hops() > MAX_HOPS) return rejected("event.rejected.maxHops", flow.id(), targetNodeId);
        FlowNode target = flow.node(targetNodeId);
        Event delivered = (target instanceof FlowNode.ProcessorNode || target instanceof FlowNode.AllocatorNode)
                ? original.withoutSession() : original;
        if (target instanceof FlowNode.ActorNode && !delivered.hasSession()) return rejected("event.rejected.actorRequiresSession", flow.id(), targetNodeId);
        if (delivered.hasSession() && !flow.id().equals(delivered.session().flowId())) return rejected("event.rejected.crossFlowSession", flow.id(), targetNodeId);
        ManagedSession owner = null;
        if (delivered.hasSession()) {
            synchronized (monitor) { owner = sessions.get(delivered.session().id()); if (owner == null || !owner.isActive()) return false; owner.work.incrementAndGet(); }
        }
        boolean accepted = running.get() && queue.offer(new RuntimeTask.EventTask(flow.id(), targetNodeId, delivered));
        if (!accepted) { if (owner != null) release(owner, null); event("queue.rejected", Map.of("task", "EventTask")); }
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
        RuntimeTask.EventTask eventTask = (RuntimeTask.EventTask) task;
        RegisteredFlow registration;
        synchronized (monitor) { registration = flows.get(eventTask.flowId()); }
        if (registration == null || registration.status != FlowStatus.ACTIVE) { releaseIfBound(eventTask.event(), null); return; }
        if (!eventTask.event().hasSession()) { workers.execute(() -> execute(registration, eventTask, null)); return; }
        ManagedSession session;
        synchronized (monitor) { session = sessions.get(eventTask.event().session().id()); }
        if (session == null || !session.isActive() || session.cancelled.get()) { releaseIfBound(eventTask.event(), null); return; }
        session.submit(() -> execute(registration, eventTask, session));
    }

    private CompletionStage<Void> execute(RegisteredFlow registration, RuntimeTask.EventTask task, ManagedSession owner) {
        KuudraFlow flow = registration.flow;
        FlowNode node;
        try { node = flow.node(task.nodeId()); }
        catch (RuntimeException error) { finishTask(owner, error); return CompletableFuture.failedFuture(error); }
        if (node instanceof FlowNode.AllocatorNode allocator) {
            allocate(flow, allocator, task.event()); finishTask(owner, null); return CompletableFuture.completedFuture(null);
        }
        EventContext baseContext = owner == null
                ? new EventContext(flow.id(), null, Map.of(), null, registration.context.snapshot(), registration.context,
                        () -> false, globalContext.snapshot(), globalContext, Map.of())
                : new EventContext(flow.id(), new SessionReference(owner.id, flow.id()), owner.context.snapshot(), owner.context,
                        registration.context.snapshot(), registration.context, owner.cancelled::get,
                        globalContext.snapshot(), globalContext, Map.of());
        EventContext context = new EventContext(baseContext.flowId(), baseContext.session(), baseContext.sessionValues(), baseContext.sessionContext(),
                baseContext.flowValues(), baseContext.flowContext(), baseContext.cancellationToken(), baseContext.globalValues(),
                baseContext.globalContext(), registration.configuration(task.nodeId()).resolve(task.event(), baseContext));
        if (node instanceof FlowNode.ActorNode actor) {
            return actor.apply(task.event(), context, output -> emitFromActor(flow, actor, task.event(), output)).handle((ignored, error) -> {
                finishTask(owner, error); return null;
            });
        }
        CompletionStage<List<Event>> stage = node instanceof FlowNode.AdapterNode adapter ? adapter.apply(task.event(), context)
                : ((FlowNode.ProcessorNode) node).apply(task.event(), context);
        return stage.handle((emitted, error) -> {
            if (error != null) { finishTask(owner, error); return null; }
            try {
                List<Event> normalized = normalize(node, task.event(), emitted);
                for (Event output : normalized) for (String next : flow.next(node.id())) enqueue(flow, next, output);
                finishTask(owner, null);
            } catch (RuntimeException failure) { finishTask(owner, failure); throw failure; }
            return null;
        });
    }

    private static Map<String, Object> configurationOf(FlowNode node) {
        if (node instanceof FlowNode.AdapterNode adapter) return adapter.configuration();
        if (node instanceof FlowNode.ProcessorNode processor) return processor.configuration();
        if (node instanceof FlowNode.ActorNode actor) return actor.configuration();
        return Map.of();
    }

    /** Actor emissions may occur before its CompletionStage completes; delivery keeps the Session alive. */
    private boolean emitFromActor(KuudraFlow flow, FlowNode.ActorNode actor, Event input, Event output) {
        try {
            Event normalized = normalize(actor, input, List.of(output)).get(0);
            boolean accepted = false;
            for (String next : flow.next(actor.id())) accepted |= enqueue(flow, next, normalized);
            return accepted;
        } catch (RuntimeException error) {
            event("actor.emit.rejected", Map.of("flowId", flow.id(), "actorId", actor.id(), "error", error.toString()));
            return false;
        }
    }

    private List<Event> normalize(FlowNode node, Event input, List<Event> emitted) {
        if (emitted == null) throw new IllegalArgumentException("component emitted null event list");
        List<Event> result = new ArrayList<>();
        for (Event output : emitted) {
            if (output == null) throw new IllegalArgumentException("component emitted null Event");
            Event derived = output.withLineage(output.lineage().descendFrom(input, false));
            if (node instanceof FlowNode.ProcessorNode) derived = derived.withoutSession();
            if ((node instanceof FlowNode.AdapterNode || node instanceof FlowNode.ActorNode) && input.hasSession()) {
                if (derived.session() != null && !derived.session().equals(input.session())) throw new IllegalArgumentException("component cannot replace a Session");
                derived = derived.withSession(input.session());
            }
            result.add(derived);
        }
        return List.copyOf(result);
    }

    private void allocate(KuudraFlow flow, FlowNode.AllocatorNode allocator, Event event) {
        if (event.hasSession()) throw new IllegalArgumentException("Allocator accepts only unbound Events");
        ManagedSession created = null;
        synchronized (monitor) {
            RegisteredFlow registration = requireFlow(flow.id()); if (registration.status != FlowStatus.ACTIVE) return;
            GroupKey key = new GroupKey(flow.id(), allocator.id(), allocator.sessionSpec().name(), allocator.sessionSpec().admissionKey());
            boolean active = sessions.values().stream().anyMatch(s -> s.isActive() && s.group.equals(key));
            switch (allocator.sessionSpec().policy()) {
                case IGNORE -> { if (active) return; }
                case TOGGLE -> { if (active) { sessions.values().stream().filter(s -> s.isActive() && s.group.equals(key)).map(s -> s.id).toList().forEach(id -> requestCancellation(id, "toggle")); return; } }
                case QUEUED -> { if (active) { queuedAllocations.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(new AllocationRequest(flow, allocator, event)); return; } }
                case PARALLEL -> { }
            }
            created = createSession(flow, allocator, event, key);
        }
        routeAllocated(created, allocator, event);
        if (requiresParentCancellation(created)) requestCancellation(created.id, "parent-already-terminal");
    }
    private ManagedSession createSession(KuudraFlow flow, FlowNode.AllocatorNode allocator, Event event, GroupKey key) {
        ManagedSession session = new ManagedSession(UUID.randomUUID(), flow, allocator.sessionSpec(), key, event.lineage().parentSessionIds(), workers);
        sessions.put(session.id, session); session.parents.forEach(parent -> childrenByParent.computeIfAbsent(parent, ignored -> new HashSet<>()).add(session.id));
        event("session.active", Map.of("sessionId", session.id.toString(), "flowId", flow.id(), "allocatorId", allocator.id())); return session;
    }
    private void routeAllocated(ManagedSession session, FlowNode.AllocatorNode allocator, Event event) {
        Event bound = event.withSession(new SessionReference(session.id, session.flow.id()));
        for (String next : session.flow.next(allocator.id())) enqueue(session.flow, next, bound);
        if (session.work.get() == 0) complete(session);
    }

    private void finishTask(ManagedSession owner, Throwable error) { if (owner != null) release(owner, error); }
    private void releaseIfBound(Event event, Throwable error) {
        if (!event.hasSession()) return; ManagedSession session; synchronized (monitor) { session = sessions.get(event.session().id()); } if (session != null) release(session, error);
    }
    private void release(ManagedSession session, Throwable error) {
        if (error != null) { fail(session, error); return; }
        if (session.work.decrementAndGet() == 0) complete(session);
    }
    private void fail(ManagedSession session, Throwable error) {
        if (!session.terminal.compareAndSet(false, true)) return;
        synchronized (monitor) { session.status = SessionStatus.FAILED; monitor.notifyAll(); }
        event("session.failed", Map.of("sessionId", session.id.toString(), "error", error.toString())); terminal(session);
    }
    private void complete(ManagedSession session) {
        if (!session.terminal.compareAndSet(false, true)) return;
        synchronized (monitor) { session.status = session.cancelled.get() ? SessionStatus.CANCELLED : SessionStatus.COMPLETED; monitor.notifyAll(); }
        event("session." + session.status.name().toLowerCase(), Map.of("sessionId", session.id.toString())); terminal(session);
    }
    private void terminal(ManagedSession session) {
        childSessions(session.id, ParentTerminationPolicy.ON_PARENT_TERMINAL).forEach(child -> requestCancellation(child, "parent-terminal"));
        admitNext(session.group); markStoppedIfDrained(session.flow.id());
    }
    private boolean requiresParentCancellation(ManagedSession child) {
        if (child.spec.parentTerminationPolicy() == ParentTerminationPolicy.NONE) return false;
        synchronized (monitor) {
            return child.parents.stream().map(sessions::get).filter(java.util.Objects::nonNull).anyMatch(parent ->
                    child.spec.parentTerminationPolicy() == ParentTerminationPolicy.ON_PARENT_TERMINAL ? !parent.isActive() : parent.cancelled.get());
        }
    }
    private List<UUID> childSessions(UUID parent, ParentTerminationPolicy required) {
        synchronized (monitor) { return childrenByParent.getOrDefault(parent, Set.of()).stream().map(sessions::get).filter(s -> s != null && s.isActive() && s.spec.parentTerminationPolicy() == required).map(s -> s.id).toList(); }
    }
    private void admitNext(GroupKey group) {
        AllocationRequest next = null;
        synchronized (monitor) {
            ArrayDeque<AllocationRequest> pending = queuedAllocations.get(group);
            if (pending != null && !pending.isEmpty() && sessions.values().stream().noneMatch(s -> s.isActive() && s.group.equals(group))) {
                next = pending.removeFirst(); if (pending.isEmpty()) queuedAllocations.remove(group);
            }
        }
        if (next != null) allocate(next.flow, next.allocator, next.event);
    }
    private void markStoppedIfDrained(String flowId) {
        boolean stopped = false;
        synchronized (monitor) {
            RegisteredFlow flow = requireFlow(flowId);
            if (flow.status == FlowStatus.STOPPING && sessions.values().stream().noneMatch(s -> s.isActive() && s.flow.id().equals(flowId))) { flow.status = FlowStatus.STOPPED; stopped = true; }
        }
        if (stopped) event("flow.stopped", Map.of("flowId", flowId));
    }
    private RegisteredFlow requireFlow(String id) { RegisteredFlow flow = flows.get(id); if (flow == null) throw new IllegalArgumentException("unknown flow: " + id); return flow; }
    private void event(String type, Map<String, Object> data) { events.publish(SystemEvent.of(type, data)); }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        for (SourceRegistration registration : sourceRegistrations) try { registration.unregister().toCompletableFuture().join(); } catch (RuntimeException ignored) { }
        synchronized (monitor) { sessions.values().stream().filter(ManagedSession::isActive).forEach(s -> s.cancelled.set(true)); }
        queue.close(); dispatcher.shutdownNow(); workers.shutdownNow(); LOG.info("KuudraRuntime stopped");
    }

    private record GroupKey(String flowId, String allocatorId, String name, String admissionKey) { }
    private record AllocationRequest(KuudraFlow flow, FlowNode.AllocatorNode allocator, Event event) { }
    private static final class RegisteredFlow {
        private final KuudraFlow flow;
        private final AtomicFlowContext context = new AtomicFlowContext();
        private final Map<String, PlaceholderResolver.CompiledMap> configurations;
        private FlowStatus status = FlowStatus.ACTIVE;
        private RegisteredFlow(KuudraFlow flow) {
            this.flow = flow;
            configurations = flow.nodes().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> PlaceholderResolver.compileMap(configurationOf(entry.getValue()))));
        }
        private PlaceholderResolver.CompiledMap configuration(String nodeId) { return configurations.get(nodeId); }
    }
    private static final class ManagedSession {
        private final UUID id; private final KuudraFlow flow; private final io.github.actforever.kuudra.api.SessionSpec spec; private final GroupKey group; private final Set<UUID> parents;
        private final AtomicBoolean cancelled = new AtomicBoolean(); private final AtomicBoolean terminal = new AtomicBoolean(); private final AtomicInteger work = new AtomicInteger(); private final AtomicSessionContext context = new AtomicSessionContext();
        private final Executor executor; private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null); private volatile SessionStatus status = SessionStatus.ACTIVE;
        private ManagedSession(UUID id, KuudraFlow flow, io.github.actforever.kuudra.api.SessionSpec spec, GroupKey group, Set<UUID> parents, Executor executor) { this.id = id; this.flow = flow; this.spec = spec; this.group = group; this.parents = Set.copyOf(parents); this.executor = executor; }
        private synchronized void submit(java.util.function.Supplier<CompletionStage<Void>> operation) { tail = tail.handle((ignored, error) -> null).thenComposeAsync(ignored -> operation.get().toCompletableFuture(), executor); }
        private boolean isActive() { return status == SessionStatus.ACTIVE || status == SessionStatus.CANCELLATION_REQUESTED; }
        private SessionSnapshot snapshot() { return new SessionSnapshot(id, flow.id(), spec.name(), spec.admissionKey(), status, cancelled.get(), parents); }
    }
    private static final class AtomicSessionContext extends AtomicValueContext implements SessionContext { }
    private static final class AtomicFlowContext extends AtomicValueContext implements FlowContext { }
    private static final class AtomicGlobalContext extends AtomicValueContext implements GlobalContext {
        private AtomicGlobalContext(Map<String, Object> initial) { super(initial); }
    }
    private static class AtomicValueContext implements ValueContext {
        private final ContextCodec codec = ContextCodecs.defaultCodec();
        private final AtomicReference<Map<String, Object>> values;
        private AtomicValueContext() { this(Map.of()); }
        @SuppressWarnings("unchecked")
        private AtomicValueContext(Map<String, Object> initial) { values = new AtomicReference<>((Map<String, Object>) codec.encode(initial)); }
        @Override public Map<String, Object> snapshot() { return values.get(); }
        @SuppressWarnings("unchecked")
        @Override public boolean compareAndSet(Map<String, Object> expected, Map<String, Object> replacement) {
            return values.compareAndSet(expected, (Map<String, Object>) codec.encode(replacement));
        }
        @SuppressWarnings("unchecked")
        @Override public Map<String, Object> update(java.util.function.UnaryOperator<Map<String, Object>> operation) {
            while (true) {
                Map<String, Object> current = values.get();
                Map<String, Object> next = (Map<String, Object>) codec.encode(operation.apply(current));
                if (values.compareAndSet(current, next)) return next;
            }
        }
        @Override public ContextCodec codec() { return codec; }
    }
}
