package io.github.actforever.kuudra.api;

import java.util.concurrent.CompletionStage;
import java.util.List;

@FunctionalInterface
public interface Actor {
    CompletionStage<List<Event>> act(Event event, ActionContext context);
}
