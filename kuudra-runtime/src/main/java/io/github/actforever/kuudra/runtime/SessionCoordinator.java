package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.*;
import java.util.*;
import java.util.function.Consumer;

/** Runtime-owned admission scheduler. It never creates or mutates Session state. */
public final class SessionCoordinator {
    private final Map<Group, State> groups = new HashMap<>();

    synchronized boolean admit(Group group, IngressConfiguration configuration, Runnable launch, Consumer<UUID> cancel) {
        State state = groups.computeIfAbsent(group, ignored -> new State());
        boolean busy = !state.active.isEmpty();
        return switch (configuration.policy()) {
            case PARALLEL -> state.active.size() < configuration.maxParallelSessions() && launch(state, launch);
            case SERIAL -> !busy ? launch(state, launch) : enqueue(state, launch, configuration.queueCapacity(), false);
            case IGNORE -> !busy && launch(state, launch);
            case CANCEL_AND_REPLACE_PENDING -> !busy ? launch(state, launch) : cancelAndQueue(state, launch, cancel, true);
            case CANCEL_AND_KEEP_PENDING -> !busy ? launch(state, launch) : cancelAndQueue(state, launch, cancel, false);
            case TOGGLE -> { if (busy) { state.active.forEach(cancel); yield false; } yield launch(state, launch); }
        };
    }
    private boolean launch(State state, Runnable launch) { state.launching++; launch.run(); return true; }
    private boolean enqueue(State state, Runnable launch, int capacity, boolean replace) {
        if (replace) state.pending.clear();
        if (state.pending.size() >= capacity) return false;
        state.pending.addLast(launch); return true;
    }
    private boolean cancelAndQueue(State state, Runnable launch, Consumer<UUID> cancel, boolean replace) {
        state.active.forEach(cancel);
        return enqueue(state, launch, 1, replace);
    }
    synchronized void activated(Group group, UUID sessionId) {
        State state = groups.get(group); if (state == null) return;
        state.launching = Math.max(0, state.launching - 1); state.active.add(sessionId);
    }
    synchronized void terminal(Group group, UUID sessionId) {
        State state = groups.get(group); if (state == null) return;
        state.active.remove(sessionId);
        if (state.active.isEmpty() && !state.pending.isEmpty()) launch(state, state.pending.removeFirst());
        if (state.active.isEmpty() && state.pending.isEmpty() && state.launching == 0) groups.remove(group);
    }
    public record Group(String scope, String ingressId, String groupKey) { }
    private static final class State { final Set<UUID> active = new LinkedHashSet<>(); final Deque<Runnable> pending = new ArrayDeque<>(); int launching; }
}
