package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.Action;
import io.github.actforever.kuudra.api.ActionCall;
import io.github.actforever.kuudra.api.Actor;
import io.github.actforever.kuudra.api.Signal;
import io.github.actforever.kuudra.api.ActionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

/** Built-in Actor that matches bindings and calls plugin/built-in Actions. */
public final class ActionActor implements Actor {
    private final List<Binding> bindings;
    public ActionActor(List<Binding> bindings) { this.bindings = List.copyOf(bindings); }
    @Override public CompletionStage<List<Signal>> act(Signal signal, ActionContext context) {
        List<Binding> matched = bindings.stream().filter(binding -> binding.when.test(signal)).toList();
        CompletableFuture<List<Signal>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (Binding binding : matched) {
            chain = chain.thenCompose(out -> binding.action.execute(new ActionCall(signal, context, binding.arguments))
                    .thenApply(result -> { out.addAll(result.emissions()); return out; }).toCompletableFuture());
        }
        return chain.thenApply(List::copyOf);
    }
    public record Binding(Predicate<Signal> when, Action action, Map<String, Object> arguments) {
        public Binding { arguments = Map.copyOf(arguments); }
    }
}
