package io.github.actforever.kuudra.runtime;

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
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Runtime-owned source of truth for Session identity, context, cancellation and work leases. */
public final class SessionManager {
    private final Object monitor = new Object();
    private final Map<UUID, ManagedSession> sessions = new LinkedHashMap<>();
    private final Executor executor;
    private final ContextCodec codec;
    private final Consumer<ManagedSession> terminalListener;
    private final Runnable controlChanged;

    SessionManager(Executor executor, ContextCodec codec, Consumer<ManagedSession> terminalListener,
                   Runnable controlChanged) {
        this.executor = executor; this.codec = codec; this.terminalListener = terminalListener;
        this.controlChanged = controlChanged;
    }

    SessionManager(Executor executor, ContextCodec codec, Consumer<ManagedSession> terminalListener) {
        this(executor, codec, terminalListener, () -> { });
    }

    ManagedSession create(String flowId, long revision, String ingressId, String groupKey,
                          Map<String, String> labels, Map<String, Object> initial) {
        return create(flowId, revision, ingressId, groupKey, labels, initial, executor);
    }

    ManagedSession create(String flowId, long revision, String ingressId, String groupKey,
                          Map<String, String> labels, Map<String, Object> initial, Executor sessionExecutor) {
        ManagedSession session = new ManagedSession(UUID.randomUUID(), flowId, revision, ingressId, groupKey, labels,
                new AtomicValueContext(codec, initial), sessionExecutor);
        synchronized (monitor) { sessions.put(session.id, session); }
        return session;
    }

    ManagedSession require(UUID id) { synchronized (monitor) { return sessions.get(id); } }
    public Optional<SessionSnapshot> snapshot(UUID id) { synchronized (monitor) { return Optional.ofNullable(sessions.get(id)).map(ManagedSession::snapshot); } }
    public List<SessionSnapshot> snapshots() { synchronized (monitor) { return sessions.values().stream().map(ManagedSession::snapshot).toList(); } }
    public int activeCount(String flowId) { synchronized (monitor) { return (int) sessions.values().stream().filter(s -> s.flowId.equals(flowId) && s.active()).count(); } }
    public boolean cancel(UUID id) {
        ManagedSession session = require(id);
        if (session == null || !session.active() || !session.cancelled.compareAndSet(false, true)) return false;
        session.status = SessionStatus.CANCELLATION_REQUESTED;
        synchronized (session.pauseMonitor) { session.paused = false; session.resumeSignal.complete(null); session.pauseMonitor.notifyAll(); }
        controlChanged.run();
        if (session.leases.get() == 0) terminate(session, SessionStatus.CANCELLED);
        return true;
    }
    public boolean pause(UUID id) {
        ManagedSession session = require(id);
        if (session == null || !session.active() || session.cancelled.get()) return false;
        synchronized (session.pauseMonitor) {
            if (session.paused) return true;
            session.paused = true; session.resumeSignal = new java.util.concurrent.CompletableFuture<>(); session.status = SessionStatus.PAUSED;
        }
        controlChanged.run();
        return true;
    }
    public boolean resume(UUID id) {
        ManagedSession session = require(id);
        if (session == null || !session.active() || session.cancelled.get()) return false;
        synchronized (session.pauseMonitor) {
            if (!session.paused) return true;
            session.paused = false; session.status = SessionStatus.ACTIVE; session.resumeSignal.complete(null); session.pauseMonitor.notifyAll();
        }
        controlChanged.run();
        if (session.leases.get() == 0) terminate(session, SessionStatus.COMPLETED);
        return true;
    }
    Set<UUID> pauseAllActive() {
        Set<UUID> changed = new LinkedHashSet<>();
        for (SessionSnapshot snapshot : snapshots()) {
            if (snapshot.status() == SessionStatus.ACTIVE && pause(snapshot.id())) changed.add(snapshot.id());
        }
        return Set.copyOf(changed);
    }
    void resumeAll(Set<UUID> sessionIds) { sessionIds.forEach(this::resume); }
    boolean acquire(ManagedSession session) { if (!session.active() || session.cancelled.get() || session.failure.get() != null) return false; session.leases.incrementAndGet(); return true; }
    void release(ManagedSession session, Throwable error) {
        if (error != null) session.failure.compareAndSet(null, error);
        int remaining = session.leases.decrementAndGet();
        if (remaining < 0) throw new KuudraException("Session lease underflow: " + session.id);
        if (remaining == 0 && !session.paused) terminate(session, session.failure.get() != null ? SessionStatus.FAILED
                : session.cancelled.get() ? SessionStatus.CANCELLED : SessionStatus.COMPLETED);
    }
    void completeIfIdle(ManagedSession session) { if (session.leases.get() == 0 && !session.paused) terminate(session, session.cancelled.get() ? SessionStatus.CANCELLED : SessionStatus.COMPLETED); }
    private void terminate(ManagedSession session, SessionStatus terminal) {
        if (!session.terminal.compareAndSet(false, true)) return;
        session.status = terminal; terminalListener.accept(session);
        synchronized (monitor) { monitor.notifyAll(); }
    }
    void cancelAll() { snapshots().stream().filter(s -> s.status() == SessionStatus.ACTIVE || s.status() == SessionStatus.PAUSED || s.status() == SessionStatus.CANCELLATION_REQUESTED).forEach(s -> cancel(s.id())); }
    void awaitDrained(long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        synchronized (monitor) { while (sessions.values().stream().anyMatch(ManagedSession::active) && System.currentTimeMillis() < end) monitor.wait(Math.max(1, end - System.currentTimeMillis())); }
    }

    static final class ManagedSession {
        final UUID id; final String flowId; final long revision; final String ingressId; final String groupKey;
        final Map<String, String> labels;
        final AtomicValueContext context; final Executor executor; final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean terminal = new AtomicBoolean(); final AtomicInteger leases = new AtomicInteger();
        volatile SessionStatus status = SessionStatus.ACTIVE;
        final Object pauseMonitor = new Object(); volatile boolean paused;
        volatile java.util.concurrent.CompletableFuture<Void> resumeSignal = java.util.concurrent.CompletableFuture.completedFuture(null);
        private java.util.concurrent.CompletableFuture<Void> serial = java.util.concurrent.CompletableFuture.completedFuture(null);
        ManagedSession(UUID id, String flowId, long revision, String ingressId, String groupKey,
                       Map<String, String> labels, AtomicValueContext context, Executor executor) {
            this.id=id; this.flowId=flowId; this.revision=revision; this.ingressId=ingressId; this.groupKey=groupKey;
            this.labels=Map.copyOf(labels); this.context=context; this.executor=executor;
        }
        boolean active() { return !terminal.get(); }
        void awaitResumed() throws InterruptedException { synchronized (pauseMonitor) { while (paused && !terminal.get() && !cancelled.get()) pauseMonitor.wait(); } }
        java.util.concurrent.CompletionStage<Void> resumed() { return resumeSignal; }
        synchronized void submit(Runnable task) { serial = serial.handle((v,e)->null).thenRunAsync(task, executor); }
        SessionReference reference() { return new SessionReference(id, flowId); }
        SessionSnapshot snapshot() { return new SessionSnapshot(id, flowId, revision, ingressId, groupKey, labels, status, cancelled.get(), leases.get()); }
    }

    static class AtomicValueContext implements SessionContext, FlowContext, AbilityContext, GlobalContext,
            EventInterpreterState {
        private final ContextCodec codec; private final AtomicReference<Map<String,Object>> values;
        AtomicValueContext(ContextCodec codec, Map<String,Object> initial) {
            this.codec=codec; Map<String,Object> encoded=new LinkedHashMap<>(); initial.forEach((k,v)->encoded.put(k,codec.encode(v))); values=new AtomicReference<>(Map.copyOf(encoded));
        }
        @Override public ContextCodec codec() { return codec; }
        public Map<String,Object> snapshot() { return values.get(); }
        public void replace(Map<String,Object> replacement) {
            Map<String,Object> encoded=new LinkedHashMap<>();
            replacement.forEach((key,value)->encoded.put(key,codec.encode(value)));
            values.set(Map.copyOf(encoded));
        }
        public boolean compareAndSet(Map<String,Object> expected,Map<String,Object> replacement){return values.compareAndSet(expected,Map.copyOf(replacement));}
        public Map<String,Object> update(java.util.function.UnaryOperator<Map<String,Object>> operation){while(true){Map<String,Object> current=values.get();Map<String,Object> next=Map.copyOf(operation.apply(current));if(values.compareAndSet(current,next))return next;}}
    }
}
