package io.github.actforever.kuudra.plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Unified lifecycle for every plugin Resource. */
public interface ResourceLifecycle extends io.github.actforever.kuudra.api.lifecycle.PausableLifecycle {
    default CompletionStage<Void> initialize(ResourceContext context) { return completed(); }
    default CompletionStage<Void> destroy() { return completed(); }
    private static CompletionStage<Void> completed() { return CompletableFuture.completedFuture(null); }
}
