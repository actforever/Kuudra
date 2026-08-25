package io.github.actforever.kuudra.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Optional non-destructive lifecycle capability; stop remains a terminal resource release. */
public interface PausableLifecycle extends Lifecycle {
    default CompletionStage<Void> pause() { return CompletableFuture.completedFuture(null); }
    default CompletionStage<Void> resume() { return CompletableFuture.completedFuture(null); }
}
