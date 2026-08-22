package io.github.actforever.kuudra.plugin;

import io.github.actforever.kuudra.api.RawSignalSource;
import io.github.actforever.kuudra.api.SourceRegistration;

import java.util.concurrent.CompletionStage;

/** Narrow runtime capability exposed to Java plugins in the first kernel. */
public interface PluginRuntimeServices {
    CompletionStage<SourceRegistration> registerRawSource(String ingressPipelineId, RawSignalSource source);

    static PluginRuntimeServices unavailable() {
        return (pipelineId, source) -> java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("This plugin manager was created without runtime services"));
    }
}
