package io.github.actforever.kuudra.api;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface Actor {
    CompletionStage<Void> act(Signal signal, ActionContext context);
}
