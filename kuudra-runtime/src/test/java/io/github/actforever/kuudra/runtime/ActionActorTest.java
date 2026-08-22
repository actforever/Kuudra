package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.ActionContext;
import io.github.actforever.kuudra.api.ActionExecutionMode;
import io.github.actforever.kuudra.api.ActionResult;
import io.github.actforever.kuudra.api.CancellationToken;
import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.SessionContext;
import io.github.actforever.kuudra.api.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionActorTest {
    private final Signal signal = new Signal(new RawSignal(UUID.randomUUID(), "input", Instant.now(), Map.of()), UUID.randomUUID(), "flow", 0);
    private final SessionContext contextStore = new SessionContext() {
        private final AtomicReference<Map<String, Object>> data = new AtomicReference<>(Map.of());
        public Map<String, Object> snapshot() { return data.get(); }
        public boolean compareAndSet(Map<String, Object> expected, Map<String, Object> replacement) { return data.compareAndSet(expected, Map.copyOf(replacement)); }
        public Map<String, Object> update(java.util.function.UnaryOperator<Map<String, Object>> op) { return data.updateAndGet(current -> Map.copyOf(op.apply(current))); }
    };
    private final ActionContext context = new ActionContext(signal.sessionId(), "flow", Map.of(), contextStore, () -> false);

    @Test
    void serialBindingsPreserveDeclarationOrder() {
        List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        var first = (io.github.actforever.kuudra.api.Action) call -> { calls.add("first"); return CompletableFuture.completedFuture(ActionResult.empty()); };
        var second = (io.github.actforever.kuudra.api.Action) call -> { calls.add("second"); return CompletableFuture.completedFuture(ActionResult.empty()); };
        ActionActor actor = new ActionActor(List.of(
                new ActionActor.Binding(s -> true, first, Map.of()), new ActionActor.Binding(s -> true, second, Map.of())));
        actor.act(signal, context).toCompletableFuture().join();
        assertEquals(List.of("first", "second"), calls);
    }

    @Test
    void parallelBindingsStartTogetherOnlyWhenExplicitlyMarked() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        var blocking = (io.github.actforever.kuudra.api.Action) call -> CompletableFuture.supplyAsync(() -> {
            bothStarted.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return ActionResult.empty();
        });
        ActionActor actor = new ActionActor(List.of(
                new ActionActor.Binding(s -> true, blocking, Map.of(), ActionExecutionMode.PARALLEL),
                new ActionActor.Binding(s -> true, blocking, Map.of(), ActionExecutionMode.PARALLEL)));
        var result = actor.act(signal, context).toCompletableFuture();
        assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
        release.countDown();
        result.join();
    }
}
