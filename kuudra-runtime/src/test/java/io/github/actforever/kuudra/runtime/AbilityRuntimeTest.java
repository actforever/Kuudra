package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.component.Ingress;
import io.github.actforever.kuudra.api.component.IngressConfiguration;
import io.github.actforever.kuudra.api.component.IngressDecision;
import io.github.actforever.kuudra.api.event.KuudraEvent;
import io.github.actforever.kuudra.api.runtime.AbilityExecutionClass;
import io.github.actforever.kuudra.api.session.SessionGroupScope;
import io.github.actforever.kuudra.api.session.SessionSchedulingPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AbilityRuntimeTest {
    @Test
    void routesCreateAndJoinThroughNamedControllerHandlerWithResolvedArguments() throws Exception {
        KuudraRuntime runtime = new KuudraRuntime(32, 4);
        try {
            Ingress ingress = (event, context) -> IngressDecision.accept(
                    context.configuration("group", String.class), event);
            Object controller = new Object();
            CountDownLatch invoked = new CountDownLatch(2);
            List<UUID> sessions = new CopyOnWriteArrayList<>();
            List<String> aliases = new CopyOnWriteArrayList<>();
            List<CompletableFuture<Void>> holds = new CopyOnWriteArrayList<>();
            FlowNode.ControllerNode action = new FlowNode.ControllerNode("action", controller, "disconnect",
                    (event, context) -> {
                        sessions.add(context.sessionId());
                        aliases.add(context.arguments().get("alias", String.class));
                        CompletableFuture<Void> hold = new CompletableFuture<>();
                        holds.add(hold); invoked.countDown(); return hold;
                    }, Map.of("alias", "${event#processAlias}"));
            IngressConfiguration scheduling = new IngressConfiguration(SessionSchedulingPolicy.PARALLEL,
                    SessionGroupScope.INGRESS, 8, 8);
            KuudraAbility ability = new KuudraAbility("demo/network", 1, Map.of(
                    "create", new FlowNode.IngressNode("create", "demo/ingress", ingress, scheduling,
                            Map.of("group", "game")),
                    "join", new FlowNode.JoinIngressNode("join", "demo/ingress", ingress, "create",
                            Map.of("group", "game")),
                    "action", action), Map.of("create", List.of("action"), "join", List.of("action")),
                    AbilityExecutionClass.DATA);
            runtime.registerAbility(ability);
            runtime.setComponentThreadSafe(controller, true);

            assertTrue(runtime.publish("demo/network", "create",
                    KuudraEvent.of("network", Map.of("processAlias", "gta"))));
            await(() -> holds.size() == 1);
            assertTrue(runtime.publish("demo/network", "join",
                    KuudraEvent.of("network", Map.of("processAlias", "launcher"))));
            assertTrue(invoked.await(3, TimeUnit.SECONDS));

            assertEquals(2, sessions.size());
            assertEquals(sessions.get(0), sessions.get(1));
            assertEquals(List.of("gta", "launcher"), aliases);
            assertEquals(1, runtime.abilities().size());
            holds.forEach(hold -> hold.complete(null));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(3)));
        } finally {
            runtime.close();
        }
    }

    @Test
    void rejectsJoinWhenNoUniqueTargetSessionExists() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        KuudraRuntime runtime = new KuudraRuntime(new InMemoryKuudraTaskQueue(16), 2, Map.of(), 64,
                event -> events.add(event.type()));
        try {
            Ingress ingress = (event, context) -> IngressDecision.accept("missing", event);
            KuudraAbility ability = new KuudraAbility("demo/join", Map.of(
                    "create", new FlowNode.IngressNode("create", ingress, IngressConfiguration.DEFAULT, Map.of()),
                    "join", new FlowNode.JoinIngressNode("join", "demo/ingress", ingress, "create", Map.of()),
                    "action", new FlowNode.ControllerNode("action", new Object(), "noop",
                            (event, context) -> CompletableFuture.completedFuture(null), Map.of())),
                    Map.of("join", List.of("action")));
            runtime.registerAbility(ability);
            assertTrue(runtime.publish("demo/join", "join", KuudraEvent.of("join", Map.of())));
            await(() -> events.contains("ingress.join.rejected"));
            assertTrue(runtime.sessions().snapshots().isEmpty());
        } finally {
            runtime.close();
        }
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.call());
    }
}
