package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.Action;
import io.github.actforever.kuudra.api.ActionCall;
import io.github.actforever.kuudra.api.Actor;
import io.github.actforever.kuudra.api.Signal;
import io.github.actforever.kuudra.api.ActionContext;
import io.github.actforever.kuudra.api.ActionExecutionMode;

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
        CompletableFuture<List<Signal>> serial = CompletableFuture.completedFuture(new ArrayList<>());
        List<CompletableFuture<List<Signal>>> parallel = new ArrayList<>();
        for (Binding binding : matched) {
            if (binding.mode == ActionExecutionMode.PARALLEL) {
                parallel.add(binding.action.execute(new ActionCall(signal, context, binding.arguments))
                        .thenApply(result -> result.emissions()).toCompletableFuture());
            } else {
                serial = serial.thenCompose(out -> binding.action.execute(new ActionCall(signal, context, binding.arguments))
                        .thenApply(result -> { out.addAll(result.emissions()); return out; }).toCompletableFuture());
            }
        }
        CompletableFuture<?>[] all = new CompletableFuture<?>[parallel.size() + 1];
        all[0] = serial;
        for (int i = 0; i < parallel.size(); i++) all[i + 1] = parallel.get(i);
        CompletableFuture<List<Signal>> serialResult = serial;
        return CompletableFuture.allOf(all).thenApply(ignored -> {
            List<Signal> emissions = new ArrayList<>(serialResult.join());
            parallel.forEach(stage -> emissions.addAll(stage.join()));
            return List.copyOf(emissions);
        });
    }
    public record Binding(Predicate<Signal> when, Action action, Map<String, Object> arguments, ActionExecutionMode mode) {
        public Binding { arguments = Map.copyOf(arguments); }
        public Binding(Predicate<Signal> when, Action action, Map<String, Object> arguments) {
            this(when, action, arguments, ActionExecutionMode.SERIAL);
        }
    }
}
