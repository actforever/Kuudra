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
    record KeyStroke(String key, boolean pressed) { }

    @Test
    void sourceAdapterProcessorAllocatorAndActorUseOneEventGraph() throws Exception {
        CountDownLatch acted = new CountDownLatch(1);
        AtomicReference<String> type = new AtomicReference<>();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 2)) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of(
                    "adapter", new FlowNode.AdapterNode("adapter", (event, context) -> List.of(event.retype("input.normalized"))),
                    "processor", new FlowNode.ProcessorNode("processor", (event, context) -> List.of(event.retype("gesture.double"))),
                    "allocate", new FlowNode.AllocatorNode("allocate", new SessionSpec("gesture", "A", SessionPolicy.PARALLEL)),
                    "actor", new FlowNode.ActorNode("actor", (event, context) -> { type.set(event.type()); assertTrue(event.hasSession()); acted.countDown(); return CompletableFuture.completedFuture(null); })
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
                    "first", new FlowNode.ActorNode("first", (event, context) -> { original.set(event.session().id()); context.emit(Event.of("actor.output", EventData.empty())); return CompletableFuture.completedFuture(null); }),
                    "same", new FlowNode.ActorNode("same", (event, context) -> { assertEquals(original.get(), event.session().id()); continued.countDown(); context.emit(event); return CompletableFuture.completedFuture(null); }),
                    "processor", new FlowNode.ProcessorNode("processor", (event, context) -> { assertFalse(event.hasSession()); return List.of(event.retype("derived")); }),
                    "child-allocate", new FlowNode.AllocatorNode("child-allocate", new SessionSpec("child", "child", SessionPolicy.PARALLEL)),
                    "child", new FlowNode.ActorNode("child", (event, context) -> { childSession.set(event.session().id()); child.countDown(); return CompletableFuture.completedFuture(null); })
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

    @Test
    void resolvesNodeConfigurationAgainstEventSessionAndGlobalContexts() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1, Map.of("profile", "demo"))) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of(
                    "allocate", new FlowNode.AllocatorNode("allocate", new SessionSpec("session", "key", SessionPolicy.PARALLEL)),
                    "set", new FlowNode.ActorNode("set", (event, context) -> {
                        context.sessionContext().update(values -> Map.of("mode", "hold"));
                        context.emit(Event.of("derived", EventData.of("input", Map.of("key", "A"))));
                        return CompletableFuture.completedFuture(null);
                    }),
                    "inspect", new FlowNode.ActorNode("inspect", (event, context) -> {
                        assertEquals("A", context.configuration().get("key"));
                        assertEquals("hold", context.configuration().get("mode"));
                        assertEquals("demo", context.configuration().get("profile"));
                        assertEquals("flow:derived", context.configuration().get("label"));
                        observed.countDown();
                        return CompletableFuture.completedFuture(null);
                    }, Map.of("key", "${event.data.input.key}", "mode", "${session.values.mode}", "profile", "${global.profile}", "label", "${flow.id}:${event.type}"))
            ), Map.of("allocate", List.of("set"), "set", List.of("inspect"))));
            assertTrue(runtime.publish("flow", "allocate", Event.of("root", EventData.empty())));
            assertTrue(observed.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneSourceStartsOnceAndFansOutToMultipleFlows() throws Exception {
        CountDownLatch delivered = new CountDownLatch(2);
        AtomicReference<io.github.actforever.kuudra.api.EventEmitter> emitter = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        EventSource source = new EventSource() {
            @Override public void setEmitter(io.github.actforever.kuudra.api.EventEmitter value) { emitter.set(value); }
            @Override public java.util.concurrent.CompletionStage<Void> start() { starts.incrementAndGet(); return CompletableFuture.completedFuture(null); }
            @Override public java.util.concurrent.CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
        };
        try (KuudraRuntime runtime = new KuudraRuntime(8, 2)) {
            for (String flowId : List.of("first", "second")) runtime.registerFlow(new KuudraFlow(flowId,
                    Map.of("sink", new FlowNode.AdapterNode("sink", (event, context) -> { delivered.countDown(); return List.of(); })), Map.of()));
            runtime.registerSource(List.of(new KuudraRuntime.SourceTarget("first", "sink"),
                    new KuudraRuntime.SourceTarget("second", "sink")), source).toCompletableFuture().join();
            assertTrue(emitter.get().emit(Event.of("test", EventData.empty())));
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
            assertEquals(1, starts.get());
        }
    }

    @Test
    void resolvesAutomaticAndExplicitScopesAndSharesTypedValues() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1, Map.of("input", Map.of("shared", "global")))) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of(
                    "allocate", new FlowNode.AllocatorNode("allocate", new SessionSpec("session", "key", SessionPolicy.PARALLEL)),
                    "set", new FlowNode.ActorNode("set", (event, context) -> {
                        context.globalContext().put("global-only", new KeyStroke("G", true));
                        context.flowContext().put("input", Map.of("shared", "flow"));
                        context.sessionContext().put("input", Map.of("shared", "session"));
                        context.sessionContext().put("stroke", new KeyStroke("A", true));
                        context.emit(Event.of("derived", EventData.of("input", Map.of("shared", "event", "stroke", new KeyStroke("E", true)))));
                        return CompletableFuture.completedFuture(null);
                    }),
                    "inspect", new FlowNode.ActorNode("inspect", (event, context) -> {
                        assertEquals("event", context.configuration().get("automatic"));
                        assertEquals("event", context.configuration().get("event"));
                        assertEquals("session", context.configuration().get("session"));
                        assertEquals("flow", context.configuration().get("flow"));
                        assertEquals("global", context.configuration().get("global"));
                        assertEquals(new KeyStroke("A", true), context.sessionContext().get("stroke", KeyStroke.class));
                        assertEquals(new KeyStroke("G", true), context.globalContext().get("global-only", KeyStroke.class));
                        assertEquals(new KeyStroke("E", true), context.configuration("stroke", KeyStroke.class));
                        assertEquals(new KeyStroke("E", true), context.configuration("direct-stroke", KeyStroke.class));
                        observed.countDown();
                        return CompletableFuture.completedFuture(null);
                    }, Map.of("automatic", "${input.shared}", "event", "${event#input.shared}",
                            "session", "${session#input.shared}", "flow", "${flow#input.shared}",
                            "global", "${global#input.shared}", "stroke", "${event#input.stroke}", "direct-stroke", "${stroke}"))
            ), Map.of("allocate", List.of("set"), "set", List.of("inspect"))));
            assertTrue(runtime.publish("flow", "allocate", Event.of("root", EventData.empty())));
            assertTrue(observed.await(1, TimeUnit.SECONDS));
        }
    }
}
