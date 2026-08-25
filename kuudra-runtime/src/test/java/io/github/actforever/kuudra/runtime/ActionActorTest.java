package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.action.ActionContext;
import io.github.actforever.kuudra.api.action.ActionResult;
import io.github.actforever.kuudra.api.event.KuudraEvent;
import io.github.actforever.kuudra.api.context.SessionContext;
import io.github.actforever.kuudra.api.context.ExecutionDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionActorTest {
    @Test
    void serialBindingsPreserveDeclarationOrder() {
        List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        var first = (io.github.actforever.kuudra.api.action.Action) call -> { calls.add("first"); return CompletableFuture.completedFuture(ActionResult.empty()); };
        var second = (io.github.actforever.kuudra.api.action.Action) call -> { calls.add("second"); return CompletableFuture.completedFuture(ActionResult.empty()); };
        ActionActor actor = new ActionActor(List.of(new ActionActor.Binding(event -> true, first, Map.of()), new ActionActor.Binding(event -> true, second, Map.of())));
        actor.handle(KuudraEvent.of("input", Map.of()), context()).toCompletableFuture().join();
        assertEquals(List.of("first", "second"), calls);
    }
    private static ActionContext context() {
        SessionContext store = new SessionContext() {
            private final AtomicReference<Map<String, Object>> data = new AtomicReference<>(Map.of());
            public Map<String, Object> snapshot() { return data.get(); }
            public boolean compareAndSet(Map<String, Object> expected, Map<String, Object> replacement) { return data.compareAndSet(expected, replacement); }
            public Map<String, Object> update(java.util.function.UnaryOperator<Map<String, Object>> op) { return data.updateAndGet(op); }
        };
        return new ActionContext(UUID.randomUUID(), "flow", Map.of(), store,
                () -> ExecutionDecision.CONTINUE, event -> true);
    }
}
