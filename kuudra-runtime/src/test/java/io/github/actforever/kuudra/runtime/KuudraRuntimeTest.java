package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.EventData;
import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuudraRuntimeTest {
    @Test
    void sourceAdapterProcessorAllocatorAndActorUseOneEventGraph() throws Exception {
        CountDownLatch acted = new CountDownLatch(1);
        AtomicReference<String> type = new AtomicReference<>();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 2)) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of(
                    "adapter", new FlowNode.AdapterNode("adapter", (event, context) -> List.of(event.retype("input.normalized"))),
                    "processor", new FlowNode.ProcessorNode("processor", (event, context) -> List.of(event.retype("gesture.double"))),
                    "allocate", new FlowNode.AllocatorNode("allocate", new SessionSpec("gesture", "A", SessionPolicy.PARALLEL)),
                    "actor", new FlowNode.ActorNode("actor", (event, context) -> { type.set(event.type()); assertTrue(event.hasSession()); acted.countDown(); return CompletableFuture.completedFuture(List.of()); })
            ), Map.of("adapter", List.of("processor"), "processor", List.of("allocate"), "allocate", List.of("actor"))));
            assertTrue(runtime.publish("flow", "adapter", Event.of("key.press", EventData.of("input", Map.of("key", "A")))));
            assertTrue(acted.await(1, TimeUnit.SECONDS));
            assertEquals("gesture.double", type.get());
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void actorEventCanContinueSameSessionOrReenterInterpretationAsChild() throws Exception {
        CountDownLatch continued = new CountDownLatch(1);
        CountDownLatch child = new CountDownLatch(1);
        AtomicReference<java.util.UUID> original = new AtomicReference<>();
        AtomicReference<java.util.UUID> childSession = new AtomicReference<>();
        try (KuudraRuntime runtime = new KuudraRuntime(64, 2)) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of(
                    "allocate", new FlowNode.AllocatorNode("allocate", new SessionSpec("root", "root", SessionPolicy.PARALLEL)),
                    "first", new FlowNode.ActorNode("first", (event, context) -> { original.set(event.session().id()); return CompletableFuture.completedFuture(List.of(Event.of("actor.output", EventData.empty()))); }),
                    "same", new FlowNode.ActorNode("same", (event, context) -> { assertEquals(original.get(), event.session().id()); continued.countDown(); return CompletableFuture.completedFuture(List.of(event)); }),
                    "processor", new FlowNode.ProcessorNode("processor", (event, context) -> { assertFalse(event.hasSession()); return List.of(event.retype("derived")); }),
                    "child-allocate", new FlowNode.AllocatorNode("child-allocate", new SessionSpec("child", "child", SessionPolicy.PARALLEL)),
                    "child", new FlowNode.ActorNode("child", (event, context) -> { childSession.set(event.session().id()); child.countDown(); return CompletableFuture.completedFuture(List.of()); })
            ), Map.of("allocate", List.of("first"), "first", List.of("same", "processor"), "processor", List.of("child-allocate"), "child-allocate", List.of("child"))));
            runtime.publish("flow", "allocate", Event.of("root", EventData.empty()));
            assertTrue(continued.await(1, TimeUnit.SECONDS)); assertTrue(child.await(1, TimeUnit.SECONDS));
            assertFalse(original.get().equals(childSession.get()));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void sourceLifecycleCanBeRegisteredAndUnregistered() {
        AtomicBoolean stopped = new AtomicBoolean();
        EventSource source = new EventSource() {
            @Override public void setEmitter(io.github.actforever.kuudra.api.EventEmitter emitter) { }
            @Override public java.util.concurrent.CompletionStage<Void> start() { return CompletableFuture.completedFuture(null); }
            @Override public java.util.concurrent.CompletionStage<Void> stop() { stopped.set(true); return CompletableFuture.completedFuture(null); }
        };
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of("adapter", new FlowNode.AdapterNode("adapter", (event, context) -> List.of(event))), Map.of()));
            runtime.registerSource("flow", "adapter", source).toCompletableFuture().join().unregister().toCompletableFuture().join();
            assertTrue(stopped.get());
        }
    }
}
