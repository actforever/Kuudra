package io.github.actforever.kuudra.api;

import java.util.concurrent.CompletionStage;

/** A component with explicit, asynchronous ownership of external resources. */
public interface Lifecycle {
    CompletionStage<Void> start();
    CompletionStage<Void> stop();
}
