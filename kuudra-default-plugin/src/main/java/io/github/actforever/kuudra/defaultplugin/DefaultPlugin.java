package io.github.actforever.kuudra.defaultplugin;

import io.github.actforever.kuudra.plugin.KuudraPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Built-in plugin identity; resource instances are still created only by manifests. */
public final class DefaultPlugin implements KuudraPlugin {
    @Override public String id() { return "default"; }
    @Override public CompletionStage<Void> start() { return CompletableFuture.completedFuture(null); }
    @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
}
