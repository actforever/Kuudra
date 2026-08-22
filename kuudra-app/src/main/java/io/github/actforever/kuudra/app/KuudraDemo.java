package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.ActionResult;
import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.EventData;
import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.runtime.ActionActor;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** EventProcessor -> SessionAllocator -> Actor end-to-end demo. */
public final class KuudraDemo {
    private KuudraDemo() { }
    public static void main(String[] args) throws Exception {
        KuudraBanner.print(); CountDownLatch acted = new CountDownLatch(1);
        var action = (io.github.actforever.kuudra.api.Action) call -> {
            System.out.printf("Action executed: simulate key C [session=%s]%n", call.context().sessionId()); acted.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(ActionResult.empty());
        };
        try (KuudraRuntime runtime = new KuudraRuntime(64, 2)) {
            runtime.registerFlow(new KuudraFlow("demo", Map.of(
                    "double-press", new FlowNode.ProcessorNode("double-press", new DoublePressProcessor()),
                    "allocate", new FlowNode.AllocatorNode("allocate", new SessionSpec("double-A", "A", SessionPolicy.PARALLEL)),
                    "actor", new FlowNode.ActorNode("actor", new ActionActor(List.of(new ActionActor.Binding(event -> event.type().equals("gesture.double-A"), action, Map.of("key", "C")))))
            ), Map.of("double-press", List.of("allocate"), "allocate", List.of("actor"))));
            runtime.publish("demo", "double-press", key()); runtime.publish("demo", "double-press", key());
            if (!acted.await(2, TimeUnit.SECONDS) || !runtime.awaitNoActiveSessions(Duration.ofSeconds(2))) throw new IllegalStateException("demo flow did not complete");
        }
        System.out.println("Kuudra demo completed successfully.");
    }
    private static Event key() { return new Event(UUID.randomUUID(), "input.key.press", Instant.now(), EventData.of("input", Map.of("key", "A")), io.github.actforever.kuudra.api.EventLineage.origin(), null); }
    private static final class DoublePressProcessor implements io.github.actforever.kuudra.api.EventProcessor {
        private Instant first;
        @Override public synchronized List<Event> process(Event event, io.github.actforever.kuudra.api.EventContext context) {
            if (!event.type().equals("input.key.press") || !"A".equals(event.data().require("input", "key"))) return List.of();
            if (first != null && !event.occurredAt().isAfter(first.plusMillis(500))) { first = null; return List.of(event.retype("gesture.double-A")); }
            first = event.occurredAt(); return List.of();
        }
    }
}
