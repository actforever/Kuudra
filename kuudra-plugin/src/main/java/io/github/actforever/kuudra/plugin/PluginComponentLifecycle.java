package io.github.actforever.kuudra.plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Optional lifecycle for a component instance created from a plugin annotation. */
public interface PluginComponentLifecycle {
    /** Runs after the owning plugin is ACTIVE and before the component enters a Flow. */
    default CompletionStage<Void> initialize(PluginComponentContext context) {
        return CompletableFuture.completedFuture(null);
    }

    /** Runs before the owning plugin is stopped, in reverse component creation order. */
    default CompletionStage<Void> destroy() {
        return CompletableFuture.completedFuture(null);
    }
}
