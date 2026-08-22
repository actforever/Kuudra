package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.ActionContext;
import io.github.actforever.kuudra.api.ActionResult;
import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.SessionContext;
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
        var first = (io.github.actforever.kuudra.api.Action) call -> { calls.add("first"); return CompletableFuture.completedFuture(ActionResult.empty()); };
        var second = (io.github.actforever.kuudra.api.Action) call -> { calls.add("second"); return CompletableFuture.completedFuture(ActionResult.empty()); };
        ActionActor actor = new ActionActor(List.of(new ActionActor.Binding(event -> true, first, Map.of()), new ActionActor.Binding(event -> true, second, Map.of())));
        actor.act(Event.of("input", Map.of()), context()).toCompletableFuture().join();
        assertEquals(List.of("first", "second"), calls);
    }
    private static ActionContext context() {
        SessionContext store = new SessionContext() {
            private final AtomicReference<Map<String, Object>> data = new AtomicReference<>(Map.of());
            public Map<String, Object> snapshot() { return data.get(); }
            public boolean compareAndSet(Map<String, Object> expected, Map<String, Object> replacement) { return data.compareAndSet(expected, replacement); }
            public Map<String, Object> update(java.util.function.UnaryOperator<Map<String, Object>> op) { return data.updateAndGet(op); }
        };
        return new ActionContext(UUID.randomUUID(), "flow", Map.of(), store, () -> false);
    }
}
