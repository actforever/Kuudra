package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.component.IngressConfiguration;
import io.github.actforever.kuudra.api.session.SessionDependencyRequirement;
import io.github.actforever.kuudra.api.session.SessionMatchPolicy;
import io.github.actforever.kuudra.api.session.SessionTerminationPolicy;

import java.util.*;
import java.util.function.Consumer;

/** Runtime-owned group scheduler and active-session dependency graph. It never mutates Session state directly. */
public final class SessionCoordinator {
    private final Map<Group, State> groups = new HashMap<>();
    private final LinkedHashMap<UUID, CoordinatedSession> sessions = new LinkedHashMap<>();
    private final Set<DependencyEdge> dependencies = new LinkedHashSet<>();

    synchronized boolean admit(Group group, IngressConfiguration configuration, Runnable launch, Consumer<UUID> cancel) {
        State state = groups.computeIfAbsent(group, ignored -> new State());
        boolean busy = !state.active.isEmpty();
        return switch (configuration.policy()) {
            case PARALLEL -> state.active.size() < configuration.maxParallelSessions() && launch(state, launch);
            case SERIAL -> !busy ? launch(state, launch) : enqueue(state, launch, configuration.queueCapacity(), false);
            case IGNORE -> !busy && launch(state, launch);
            case CANCEL_AND_REPLACE_PENDING -> !busy ? launch(state, launch) : cancelAndQueue(state, launch, cancel, true);
            case CANCEL_AND_KEEP_PENDING -> !busy ? launch(state, launch) : cancelAndQueue(state, launch, cancel, false);
            case TOGGLE -> {
                if (busy) {
                    List.copyOf(state.active).forEach(cancel);
                    yield false;
                }
                yield launch(state, launch);
            }
        };
    }

    private boolean launch(State state, Runnable launch) {
        state.launching++;
        launch.run();
        return true;
    }

    private boolean enqueue(State state, Runnable launch, int capacity, boolean replace) {
        if (replace) state.pending.clear();
        if (state.pending.size() >= capacity) return false;
        state.pending.addLast(launch);
        return true;
    }

    private boolean cancelAndQueue(State state, Runnable launch, Consumer<UUID> cancel, boolean replace) {
        boolean accepted = enqueue(state, launch, 1, replace);
        if (accepted) List.copyOf(state.active).forEach(cancel);
        return accepted;
    }

    /** Atomically registers a session and resolves all dependency selectors against active sessions. */
    synchronized boolean activated(Group group, CoordinatedSession session,
                                   List<SessionDependencyRequirement> requirements) {
        State state = groups.get(group);
        if (state == null) return false;
        state.launching = Math.max(0, state.launching - 1);

        List<DependencyEdge> resolved = new ArrayList<>();
        for (SessionDependencyRequirement requirement : requirements) {
            List<CoordinatedSession> matches = sessions.values().stream()
                    .filter(candidate -> session.flowId().equals(candidate.flowId()))
                    .filter(candidate -> matches(requirement, candidate))
                    .toList();
            if (matches.isEmpty()
                    || requirement.selector().matchPolicy() == SessionMatchPolicy.UNIQUE && matches.size() != 1) {
                cleanupGroupIfIdle(group, state);
                return false;
            }
            List<CoordinatedSession> selected = switch (requirement.selector().matchPolicy()) {
                case UNIQUE -> matches;
                case LATEST -> List.of(matches.get(matches.size() - 1));
                case ALL -> matches;
            };
            selected.forEach(required -> resolved.add(new DependencyEdge(
                    session.id(), required.id(), requirement.terminationPolicy())));
        }

        sessions.put(session.id(), session);
        dependencies.addAll(resolved);
        state.active.add(session.id());
        return true;
    }

    private boolean matches(SessionDependencyRequirement requirement, CoordinatedSession candidate) {
        var selector = requirement.selector();
        return selector.matchLabels().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(candidate.labels().get(entry.getKey())));
    }

    void terminal(Group group, UUID sessionId, Consumer<UUID> cancel) {
        Set<UUID> cancellations = new LinkedHashSet<>();
        synchronized (this) {
            State state = groups.get(group);
            if (state != null) state.active.remove(sessionId);
            sessions.remove(sessionId);

            Iterator<DependencyEdge> iterator = dependencies.iterator();
            while (iterator.hasNext()) {
                DependencyEdge edge = iterator.next();
                if (edge.requiredSessionId().equals(sessionId)
                        && (edge.terminationPolicy() == SessionTerminationPolicy.CANCEL_DEPENDENT
                        || edge.terminationPolicy() == SessionTerminationPolicy.CANCEL_BOTH)) {
                    cancellations.add(edge.dependentSessionId());
                }
                if (edge.dependentSessionId().equals(sessionId)
                        && (edge.terminationPolicy() == SessionTerminationPolicy.CANCEL_REQUIRED
                        || edge.terminationPolicy() == SessionTerminationPolicy.CANCEL_BOTH)) {
                    cancellations.add(edge.requiredSessionId());
                }
                if (edge.dependentSessionId().equals(sessionId) || edge.requiredSessionId().equals(sessionId)) {
                    iterator.remove();
                }
            }
            cancellations.remove(sessionId);
            if (state != null) {
                if (state.active.isEmpty() && !state.pending.isEmpty()) launch(state, state.pending.removeFirst());
                cleanupGroupIfIdle(group, state);
            }
        }
        cancellations.forEach(cancel);
    }

    synchronized List<DependencyEdge> dependencySnapshot() {
        return List.copyOf(dependencies);
    }

    private void cleanupGroupIfIdle(Group group, State state) {
        if (state.active.isEmpty() && state.pending.isEmpty() && state.launching == 0) groups.remove(group);
    }

    public record Group(String scope, String ingressId, String groupKey) { }

    record CoordinatedSession(UUID id, String flowId, String ingressComponentId, String groupKey,
                              Map<String, String> labels) {
        CoordinatedSession {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(flowId, "flowId");
            Objects.requireNonNull(ingressComponentId, "ingressComponentId");
            Objects.requireNonNull(groupKey, "groupKey");
            labels = Map.copyOf(labels);
        }
    }

    record DependencyEdge(UUID dependentSessionId, UUID requiredSessionId,
                          SessionTerminationPolicy terminationPolicy) { }

    private static final class State {
        final Set<UUID> active = new LinkedHashSet<>();
        final Deque<Runnable> pending = new ArrayDeque<>();
        int launching;
    }
}
