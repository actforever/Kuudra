package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.runtime.IngressPipeline;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies the two admission policies whose behavior cannot be seen in the double-click demo. */
public final class SessionPolicyCheck {
    private SessionPolicyCheck() { }

    public static void main(String[] args) throws Exception {
        verifyQueued();
        verifyToggle();
        System.out.println("SessionPolicyCheck passed");
    }

    private static void verifyQueued() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<Void> firstGate = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            install(runtime, "queued", SessionPolicy.QUEUED, (signal, context) -> {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    return firstGate;
                }
                secondStarted.countDown();
                return CompletableFuture.completedFuture(null);
            });
            runtime.publish("in", signal());
            if (!firstStarted.await(1, TimeUnit.SECONDS)) throw new AssertionError("first queued action did not start");
            runtime.publish("in", signal());
            firstGate.complete(null);
            if (!secondStarted.await(1, TimeUnit.SECONDS)) throw new AssertionError("queued signal did not run after first session");
            if (!runtime.awaitNoActiveSessions(Duration.ofSeconds(1))) throw new AssertionError("queued sessions did not finish");
        }
    }

    private static void verifyToggle() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicReference<io.github.actforever.kuudra.api.CancellationToken> token = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            install(runtime, "toggle", SessionPolicy.TOGGLE, (signal, context) -> {
                calls.incrementAndGet();
                token.set(context.cancellationToken());
                started.countDown();
                return gate;
            });
            runtime.publish("in", signal());
            if (!started.await(1, TimeUnit.SECONDS)) throw new AssertionError("toggle action did not start");
            runtime.publish("in", signal());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!token.get().isCancellationRequested() && System.nanoTime() < deadline) Thread.onSpinWait();
            if (!token.get().isCancellationRequested()) throw new AssertionError("toggle did not cancel active session");
            if (calls.get() != 1) throw new AssertionError("toggle incorrectly created another session");
            gate.complete(null);
            if (!runtime.awaitNoActiveSessions(Duration.ofSeconds(1))) throw new AssertionError("cancelled session did not finish");
        }
    }

    private static void install(KuudraRuntime runtime, String flowId, SessionPolicy policy,
                                io.github.actforever.kuudra.api.Actor actor) {
        runtime.registerFlow(new KuudraFlow(flowId, (raw, state) -> Optional.of(
                new SessionSpec("group", "key", policy)), List.of(actor)));
        runtime.registerIngress(new IngressPipeline("in", List.of(), List.of(
                new IngressPipeline.Output(raw -> true, flowId))));
    }

    private static RawSignal signal() {
        return RawSignal.of("check", Map.of());
    }
}
