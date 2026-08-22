package io.github.actforever.kuudra.api;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface Actor {
    /**
     * Handles one session-bound Event. Use {@link ActionContext#emit(Event)} to
     * publish derived Events at any point during asynchronous execution.
     */
    CompletionStage<Void> act(Event event, ActionContext context);
}
