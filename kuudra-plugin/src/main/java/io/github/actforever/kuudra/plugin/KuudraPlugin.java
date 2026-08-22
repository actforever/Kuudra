package io.github.actforever.kuudra.plugin;

import java.util.concurrent.CompletionStage;

/** Minimal lifecycle contract; ClassLoader loading is deliberately outside the first demo. */
public interface KuudraPlugin {
    String id();
    CompletionStage<Void> start();
    CompletionStage<Void> stop();
}
