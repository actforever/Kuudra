package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.RootSignal;
import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.api.Signal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuudraRuntimeTest {
    @Test
    void rawAdapterProcessorAndActorFormASessionBoundGraph() throws Exception {
        CountDownLatch acted = new CountDownLatch(1);
        AtomicReference<String> observedType = new AtomicReference<>();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 2)) {
            runtime.registerFlow(new KuudraFlow("flow", rootProcessor(SessionPolicy.PARALLEL), "adapter",
                    Map.of(
                            "adapter", new FlowNode.AdapterNode("adapter", (signal, ctx) -> List.of(retype(signal, "stage.adapter"))),
                            "processor", new FlowNode.ProcessorNode("processor", (signal, ctx) -> List.of(retype(signal, "stage.processor"))),
                            "actor", new FlowNode.ActorNode("actor", (signal, ctx) -> {
                                observedType.set(signal.raw().type()); acted.countDown(); return CompletableFuture.completedFuture(List.of());
                            })
                    ), Map.of("adapter", List.of("processor"), "processor", List.of("actor"))));
            runtime.registerIngress(new IngressPipeline("in", List.of(), List.of(new IngressPipeline.Output(raw -> true, "flow"))));
            assertTrue(runtime.publishRaw("in", raw("input")));
            assertTrue(acted.await(1, TimeUnit.SECONDS));
            assertEquals("stage.processor", observedType.get());
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void queuedPolicyStartsSecondRootOnlyAfterFirstSessionCompletes() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<List<Signal>> gate = new CompletableFuture<>();
        AtomicInteger count = new AtomicInteger();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1)) {
            runtime.registerFlow(oneActorFlow("queued", SessionPolicy.QUEUED, (signal, ctx) -> {
                if (count.incrementAndGet() == 1) { firstStarted.countDown(); return gate; }
                secondStarted.countDown(); return CompletableFuture.completedFuture(List.of());
            }));
            runtime.publishRoot(root("queued", SessionPolicy.QUEUED));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            runtime.publishRoot(root("queued", SessionPolicy.QUEUED));
            gate.complete(List.of());
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void toggleRequestsCancellationWithoutCreatingAnotherSession() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<List<Signal>> gate = new CompletableFuture<>();
        AtomicReference<io.github.actforever.kuudra.api.CancellationToken> token = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1)) {
            runtime.registerFlow(oneActorFlow("toggle", SessionPolicy.TOGGLE, (signal, ctx) -> {
                count.incrementAndGet(); token.set(ctx.cancellationToken()); started.countDown(); return gate;
            }));
            runtime.publishRoot(root("toggle", SessionPolicy.TOGGLE));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            runtime.publishRoot(root("toggle", SessionPolicy.TOGGLE));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!token.get().isCancellationRequested() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(token.get().isCancellationRequested());
            assertEquals(1, count.get());
            gate.complete(List.of());
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    private static KuudraFlow oneActorFlow(String id, SessionPolicy policy, io.github.actforever.kuudra.api.Actor actor) {
        return new KuudraFlow(id, rootProcessor(policy), "actor", Map.of("actor", new FlowNode.ActorNode("actor", actor)), Map.of());
    }
    private static io.github.actforever.kuudra.api.SessionProcessor rootProcessor(SessionPolicy policy) {
        return (raw, context) -> List.of(context.root(raw, new SessionSpec("session", "key", policy)));
    }
    private static RootSignal root(String flow, SessionPolicy policy) {
        return RootSignal.of(raw("root"), flow, new SessionSpec("session", "key", policy));
    }
    private static RawSignal raw(String type) {
        return new RawSignal(java.util.UUID.randomUUID(), type, Instant.now(), Map.of());
    }
    private static Signal retype(Signal signal, String type) {
        return new Signal(new RawSignal(signal.raw().id(), type, signal.raw().occurredAt(), signal.raw().payload()),
                signal.sessionId(), signal.flowId(), signal.sequence());
    }
}
