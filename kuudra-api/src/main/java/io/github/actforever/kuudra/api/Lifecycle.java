package io.github.actforever.kuudra.api;

import java.util.concurrent.CompletionStage;

/** A component with explicit, asynchronous ownership of external resources. */
public interface Lifecycle {
    default CompletionStage<Void> start() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    default CompletionStage<Void> stop() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
}
