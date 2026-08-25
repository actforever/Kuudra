package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.action.Action;
import io.github.actforever.kuudra.api.action.ActionCall;
import io.github.actforever.kuudra.api.component.EventHandler;
import io.github.actforever.kuudra.api.event.KuudraEvent;
import io.github.actforever.kuudra.api.action.ActionContext;
import io.github.actforever.kuudra.api.action.ActionExecutionMode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

/** Built-in EventHandler that matches bindings and calls plugin/built-in Actions. */
public final class ActionActor implements EventHandler {
    private final List<Binding> bindings;
    public ActionActor(List<Binding> bindings) { this.bindings = List.copyOf(bindings); }
    @Override public CompletionStage<Void> handle(KuudraEvent event, ActionContext context) {
        List<Binding> matched = bindings.stream().filter(binding -> binding.when.test(event)).toList();
        CompletableFuture<Void> serial = CompletableFuture.completedFuture(null);
        List<CompletableFuture<Void>> parallel = new java.util.ArrayList<>();
        for (Binding binding : matched) {
            if (binding.mode == ActionExecutionMode.PARALLEL) {
                parallel.add(binding.action.execute(new ActionCall(event, context, binding.arguments))
                        .thenAccept(result -> result.emissions().forEach(context::emit)).toCompletableFuture());
            } else {
                serial = serial.thenCompose(ignored -> binding.action.execute(new ActionCall(event, context, binding.arguments))
                        .thenAccept(result -> result.emissions().forEach(context::emit)).toCompletableFuture());
            }
        }
        CompletableFuture<?>[] all = new CompletableFuture<?>[parallel.size() + 1];
        all[0] = serial;
        for (int i = 0; i < parallel.size(); i++) all[i + 1] = parallel.get(i);
        return CompletableFuture.allOf(all);
    }
    public record Binding(Predicate<KuudraEvent> when, Action action, Map<String, Object> arguments, ActionExecutionMode mode) {
        public Binding { arguments = Map.copyOf(arguments); }
        public Binding(Predicate<KuudraEvent> when, Action action, Map<String, Object> arguments) {
            this(when, action, arguments, ActionExecutionMode.SERIAL);
        }
    }
}
