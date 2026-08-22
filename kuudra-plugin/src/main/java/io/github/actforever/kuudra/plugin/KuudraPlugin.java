package io.github.actforever.kuudra.plugin;

import java.util.concurrent.CompletionStage;

/** Minimal lifecycle contract; ClassLoader loading is deliberately outside the first demo. */
public interface KuudraPlugin {
    String id();

    /**
     * Declares the plugin dependency graph.  The default keeps the original
     * one-method identity contract convenient for very small plugins.
     */
    default PluginDescriptor descriptor() {
        return new PluginDescriptor(id(), java.util.List.of());
    }

    /** Invoked after a private plugin home has been prepared, before start. */
    default CompletionStage<Void> initialize(PluginContext context) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    CompletionStage<Void> start();
    CompletionStage<Void> stop();

    /** Invoked after stop, in reverse dependency order. */
    default CompletionStage<Void> destroy() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
