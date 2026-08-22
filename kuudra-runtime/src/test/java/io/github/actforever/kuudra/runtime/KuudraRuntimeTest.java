package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.RawSignalEmitter;
import io.github.actforever.kuudra.api.RawSignalSource;
import io.github.actforever.kuudra.api.RootSignal;
import io.github.actforever.kuudra.api.RootSignalEmitter;
import io.github.actforever.kuudra.api.RootSignalSource;
import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.api.SessionStatus;
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

    @Test
    void rawAndRootSourcesAreLifecycleManagedAndSystemEventsAreObservable() throws Exception {
        CountDownLatch rawActed = new CountDownLatch(1);
        CountDownLatch rootActed = new CountDownLatch(1);
        CountDownLatch completedEvent = new CountDownLatch(2);
        TestRawSource rawSource = new TestRawSource(raw("source.raw"));
        TestRootSource rootSource = new TestRootSource(root("root-flow", SessionPolicy.PARALLEL));
        KuudraRuntime runtime = new KuudraRuntime(32, 1);
        try {
            runtime.systemEvents().subscribe(event -> { if (event.type().startsWith("session.completed")) completedEvent.countDown(); });
            runtime.registerFlow(oneActorFlow("raw-flow", SessionPolicy.PARALLEL, (signal, context) -> {
                rawActed.countDown(); return CompletableFuture.completedFuture(List.of());
            }));
            runtime.registerFlow(oneActorFlow("root-flow", SessionPolicy.PARALLEL, (signal, context) -> {
                rootActed.countDown(); return CompletableFuture.completedFuture(List.of());
            }));
            runtime.registerIngress(new IngressPipeline("source-in", List.of(), List.of(new IngressPipeline.Output(raw -> true, "raw-flow"))));
            runtime.registerSource("source-in", rawSource).toCompletableFuture().join();
            runtime.registerRootSource(rootSource).toCompletableFuture().join();
            assertTrue(rawActed.await(1, TimeUnit.SECONDS));
            assertTrue(rootActed.await(1, TimeUnit.SECONDS));
            assertTrue(completedEvent.await(1, TimeUnit.SECONDS));
        } finally {
            runtime.close();
        }
        assertTrue(rawSource.stopped.get());
        assertTrue(rootSource.stopped.get());
    }

    @Test
    void sourceRegistrationCanBeExplicitlyUnregisteredBeforeRuntimeShutdown() {
        TestRawSource source = new TestRawSource(raw("unregister.raw"));
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1)) {
            runtime.registerIngress(new IngressPipeline("unregister-in", List.of(), List.of()));
            var registration = runtime.registerSource("unregister-in", source).toCompletableFuture().join();
            registration.unregister().toCompletableFuture().join();
            assertTrue(source.stopped.get());
            registration.unregister().toCompletableFuture().join();
        }
    }

    @Test
    void actionCanAtomicallyUpdateSessionContextForLaterGraphNodes() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        var write = (io.github.actforever.kuudra.api.Action) call -> {
            call.context().sessionContext().update(values -> Map.of("combo", 2));
            return CompletableFuture.completedFuture(new io.github.actforever.kuudra.api.ActionResult(List.of(call.signal())));
        };
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1)) {
            runtime.registerFlow(new KuudraFlow("context", rootProcessor(SessionPolicy.PARALLEL), "write",
                    Map.of(
                            "write", new FlowNode.ActorNode("write", new ActionActor(List.of(new ActionActor.Binding(s -> true, write, Map.of())))),
                            "read", new FlowNode.ActorNode("read", (signal, context) -> {
                                assertEquals(2, context.sessionValues().get("combo"));
                                observed.countDown();
                                return CompletableFuture.completedFuture(List.of());
                            })
                    ), Map.of("write", List.of("read"))));
            runtime.publishRoot(root("context", SessionPolicy.PARALLEL));
            assertTrue(observed.await(1, TimeUnit.SECONDS));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void pauseDefersSessionContinuationUntilResume() throws Exception {
        CompletableFuture<List<Signal>> gate = new CompletableFuture<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);
        AtomicReference<Signal> firstSignal = new AtomicReference<>();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1)) {
            runtime.registerFlow(new KuudraFlow("pause", rootProcessor(SessionPolicy.PARALLEL), "first",
                    Map.of(
                            "first", new FlowNode.ActorNode("first", (signal, context) -> { firstSignal.set(signal); firstStarted.countDown(); return gate; }),
                            "second", new FlowNode.ActorNode("second", (signal, context) -> { secondRan.countDown(); return CompletableFuture.completedFuture(List.of()); })
                    ), Map.of("first", List.of("second"))));
            runtime.publishRoot(root("pause", SessionPolicy.PARALLEL));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            runtime.pauseFlow("pause");
            gate.complete(List.of(firstSignal.get()));
            Thread.sleep(50);
            assertEquals(1, runtime.flow("pause").orElseThrow().deferredTasks());
            runtime.resumeFlow("pause");
            assertTrue(secondRan.await(1, TimeUnit.SECONDS));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void parallelPolicyLetsDifferentSessionsRunConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<CompletableFuture<List<Signal>>> gates = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 2)) {
            runtime.registerFlow(oneActorFlow("parallel", SessionPolicy.PARALLEL, (signal, context) -> {
                CompletableFuture<List<Signal>> gate = new CompletableFuture<>();
                gates.add(gate); bothStarted.countDown(); return gate;
            }));
            runtime.publishRoot(root("parallel", SessionPolicy.PARALLEL));
            runtime.publishRoot(root("parallel", SessionPolicy.PARALLEL));
            assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
            assertEquals(2, runtime.activeSessionCount("parallel", "session"));
            gates.forEach(gate -> gate.complete(List.of()));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void ignorePolicyDropsNewRootsWhileTheAdmissionGroupIsActive() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<List<Signal>> gate = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1)) {
            runtime.registerFlow(oneActorFlow("ignore", SessionPolicy.IGNORE, (signal, context) -> {
                calls.incrementAndGet(); started.countDown(); return gate;
            }));
            runtime.publishRoot(root("ignore", SessionPolicy.IGNORE));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            runtime.publishRoot(root("ignore", SessionPolicy.IGNORE));
            Thread.sleep(50);
            assertEquals(1, calls.get());
            gate.complete(List.of());
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void actionFailureMarksTheSessionFailedAndPublishesAnEvent() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<java.util.UUID> sessionId = new AtomicReference<>();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1)) {
            runtime.systemEvents().subscribe(event -> {
                if (event.type().equals("session.failed")) {
                    sessionId.set(java.util.UUID.fromString((String) event.data().get("sessionId")));
                    failed.countDown();
                }
            });
            runtime.registerFlow(oneActorFlow("failing", SessionPolicy.PARALLEL,
                    (signal, context) -> CompletableFuture.failedFuture(new IllegalStateException("expected"))));
            runtime.publishRoot(root("failing", SessionPolicy.PARALLEL));
            assertTrue(failed.await(1, TimeUnit.SECONDS));
            assertEquals(SessionStatus.FAILED, runtime.session(sessionId.get()).orElseThrow().status());
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void stoppingAPausedFlowDrainsItsDeferredContinuationCooperatively() throws Exception {
        CompletableFuture<List<Signal>> gate = new CompletableFuture<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch continuationRan = new CountDownLatch(1);
        AtomicReference<Signal> firstSignal = new AtomicReference<>();
        try (KuudraRuntime runtime = new KuudraRuntime(32, 1)) {
            runtime.registerFlow(new KuudraFlow("stopping", rootProcessor(SessionPolicy.PARALLEL), "first",
                    Map.of(
                            "first", new FlowNode.ActorNode("first", (signal, context) -> { firstSignal.set(signal); firstStarted.countDown(); return gate; }),
                            "next", new FlowNode.ActorNode("next", (signal, context) -> { continuationRan.countDown(); return CompletableFuture.completedFuture(List.of()); })
                    ), Map.of("first", List.of("next"))));
            runtime.publishRoot(root("stopping", SessionPolicy.PARALLEL));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            runtime.pauseFlow("stopping");
            gate.complete(List.of(firstSignal.get()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (runtime.flow("stopping").orElseThrow().deferredTasks() != 1 && System.nanoTime() < deadline) Thread.onSpinWait();
            runtime.stopFlow("stopping");
            assertTrue(continuationRan.await(1, TimeUnit.SECONDS));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
            long stoppedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (runtime.flow("stopping").orElseThrow().status() != io.github.actforever.kuudra.api.FlowStatus.STOPPED
                    && System.nanoTime() < stoppedDeadline) Thread.onSpinWait();
            assertEquals(io.github.actforever.kuudra.api.FlowStatus.STOPPED, runtime.flow("stopping").orElseThrow().status());
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

    private static final class TestRawSource implements RawSignalSource {
        private final RawSignal signal; private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        private RawSignalEmitter emitter;
        private TestRawSource(RawSignal signal) { this.signal = signal; }
        @Override public void setEmitter(RawSignalEmitter emitter) { this.emitter = emitter; }
        @Override public java.util.concurrent.CompletionStage<Void> start() { emitter.emit(signal); return CompletableFuture.completedFuture(null); }
        @Override public java.util.concurrent.CompletionStage<Void> stop() { stopped.set(true); return CompletableFuture.completedFuture(null); }
    }
    private static final class TestRootSource implements RootSignalSource {
        private final RootSignal signal; private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        private RootSignalEmitter emitter;
        private TestRootSource(RootSignal signal) { this.signal = signal; }
        @Override public void setEmitter(RootSignalEmitter emitter) { this.emitter = emitter; }
        @Override public java.util.concurrent.CompletionStage<Void> start() { emitter.emit(signal); return CompletableFuture.completedFuture(null); }
        @Override public java.util.concurrent.CompletionStage<Void> stop() { stopped.set(true); return CompletableFuture.completedFuture(null); }
    }
}
