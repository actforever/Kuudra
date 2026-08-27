package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.component.IngressConfiguration;
import io.github.actforever.kuudra.api.session.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SessionCoordinatorTest {
    private static final IngressConfiguration PARALLEL = new IngressConfiguration(
            SessionSchedulingPolicy.PARALLEL, SessionGroupScope.FLOW_BINDING, 16, 16);

    @Test
    void requiredTerminationCancelsDependentAndRemovesGraphEdge() {
        SessionCoordinator coordinator = new SessionCoordinator();
        SessionCoordinator.Group requiredGroup = group("required");
        UUID required = activate(coordinator, requiredGroup, "flow", "ingress/test/b", "window", List.of());

        SessionDependencyRequirement requirement = requirement("flow-b", "ingress/test/b", "window",
                SessionMatchPolicy.UNIQUE, SessionTerminationPolicy.CANCEL_DEPENDENT);
        SessionCoordinator.Group dependentGroup = group("dependent");
        UUID dependent = activate(coordinator, dependentGroup, "flow", "ingress/test/a", "job", List.of(requirement));
        assertEquals(1, coordinator.dependencySnapshot().size());

        List<UUID> cancelled = new ArrayList<>();
        coordinator.terminal(requiredGroup, required, cancelled::add);

        assertEquals(List.of(dependent), cancelled);
        assertTrue(coordinator.dependencySnapshot().isEmpty());
    }

    @Test
    void allSelectorBuildsAGraphAndBidirectionalPolicyPropagatesBackwards() {
        SessionCoordinator coordinator = new SessionCoordinator();
        SessionCoordinator.Group requiredGroup = group("required");
        UUID first = activate(coordinator, requiredGroup, "flow", "ingress/test/b", "window", List.of());
        UUID second = activate(coordinator, requiredGroup, "flow", "ingress/test/b", "window", List.of());

        SessionDependencyRequirement requirement = requirement("flow-b", "ingress/test/b", "window",
                SessionMatchPolicy.ALL, SessionTerminationPolicy.CANCEL_BOTH);
        SessionCoordinator.Group dependentGroup = group("dependent");
        UUID dependent = activate(coordinator, dependentGroup, "flow", "ingress/test/a", "job", List.of(requirement));
        assertEquals(2, coordinator.dependencySnapshot().size());

        Set<UUID> cancelled = new LinkedHashSet<>();
        coordinator.terminal(dependentGroup, dependent, cancelled::add);
        assertEquals(Set.of(first, second), cancelled);
    }

    @Test
    void serialPendingAdmissionResolvesDependenciesOnlyWhenItActuallyLaunches() {
        SessionCoordinator coordinator = new SessionCoordinator();
        SessionCoordinator.Group requiredGroup = group("required");
        UUID required = activate(coordinator, requiredGroup, "flow", "ingress/test/b", "window", List.of());
        SessionCoordinator.Group serialGroup = group("serial");
        IngressConfiguration serial = new IngressConfiguration(
                SessionSchedulingPolicy.SERIAL, SessionGroupScope.FLOW_BINDING, 1, 4);
        UUID active = activate(coordinator, serialGroup, "flow", "ingress/test/a", "job", List.of());

        SessionDependencyRequirement requirement = requirement("flow-b", "ingress/test/b", "window",
                SessionMatchPolicy.UNIQUE, SessionTerminationPolicy.CANCEL_DEPENDENT);
        AtomicBoolean dependencyResolved = new AtomicBoolean(true);
        Runnable pendingLaunch = () -> {
            UUID id = UUID.randomUUID();
            dependencyResolved.set(coordinator.activated(serialGroup,
                    new SessionCoordinator.CoordinatedSession(id, "flow", "ingress/test/a", "job", Map.of("role", "job")),
                    List.of(requirement)));
        };
        assertTrue(coordinator.admit(serialGroup, serial, pendingLaunch, ignored -> { }));

        coordinator.terminal(requiredGroup, required, ignored -> { });
        coordinator.terminal(serialGroup, active, ignored -> { });
        assertFalse(dependencyResolved.get());
    }

    @Test
    void dependencyLabelsNeverMatchAcrossFlows() {
        SessionCoordinator coordinator = new SessionCoordinator();
        activate(coordinator, group("required"), "other-flow", "ingress/test/b", "window", List.of());
        SessionCoordinator.Group dependentGroup = group("dependent");
        AtomicBoolean activated = new AtomicBoolean(true);
        assertTrue(coordinator.admit(dependentGroup, PARALLEL, () -> activated.set(coordinator.activated(
                dependentGroup,
                new SessionCoordinator.CoordinatedSession(UUID.randomUUID(), "current-flow", "ingress/test/a",
                        "job", Map.of("role", "job")),
                List.of(requirement(null, null, "window", SessionMatchPolicy.UNIQUE,
                        SessionTerminationPolicy.CANCEL_DEPENDENT)))), ignored -> { }));
        assertFalse(activated.get());
    }

    @Test
    void uniqueSelectorRejectsAmbiguousActiveSessions() {
        SessionCoordinator coordinator = new SessionCoordinator();
        SessionCoordinator.Group requiredGroup = group("required");
        activate(coordinator, requiredGroup, "flow", "ingress/test/b", "window", List.of());
        activate(coordinator, requiredGroup, "flow", "ingress/test/b", "window", List.of());
        SessionCoordinator.Group dependentGroup = group("dependent");
        AtomicBoolean activated = new AtomicBoolean(true);
        Runnable launch = () -> activated.set(coordinator.activated(dependentGroup,
                new SessionCoordinator.CoordinatedSession(UUID.randomUUID(), "flow", "ingress/test/a", "job", Map.of("role", "job")),
                List.of(requirement("flow-b", "ingress/test/b", "window", SessionMatchPolicy.UNIQUE,
                        SessionTerminationPolicy.CANCEL_DEPENDENT))));
        assertTrue(coordinator.admit(dependentGroup, PARALLEL, launch, ignored -> { }));
        assertFalse(activated.get());
    }

    private static UUID activate(SessionCoordinator coordinator, SessionCoordinator.Group group, String flowId,
                                 String ingressId, String groupKey,
                                 List<SessionDependencyRequirement> requirements) {
        UUID id = UUID.randomUUID();
        AtomicBoolean activated = new AtomicBoolean();
        assertTrue(coordinator.admit(group, PARALLEL, () -> activated.set(coordinator.activated(group,
                new SessionCoordinator.CoordinatedSession(id, flowId, ingressId, groupKey, Map.of("role", groupKey)), requirements)), ignored -> { }));
        assertTrue(activated.get());
        return id;
    }

    private static SessionDependencyRequirement requirement(String flowId, String ingressId, String groupKey,
                                                            SessionMatchPolicy match,
                                                            SessionTerminationPolicy termination) {
        return new SessionDependencyRequirement(new SessionSelector(Map.of("role", groupKey), match), termination);
    }

    private static SessionCoordinator.Group group(String name) {
        return new SessionCoordinator.Group("scope-" + name, "ingress-" + name, "group-" + name);
    }
}
