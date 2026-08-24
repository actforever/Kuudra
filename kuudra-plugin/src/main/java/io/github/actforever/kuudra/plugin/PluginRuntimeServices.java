package io.github.actforever.kuudra.plugin;

import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.api.SourceRegistration;

import java.util.concurrent.CompletionStage;
import java.util.UUID;

/** Narrow runtime capability exposed to Java plugins in the first kernel. */
public interface PluginRuntimeServices {
    CompletionStage<SourceRegistration> registerEventSource(String flowId, String targetNodeId, EventSource source);

    /** Requests kernel/session control without exposing the App implementation to plugins. */
    default CompletionStage<Void> control(KernelControlAction action, UUID sessionId) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("Kernel control is unavailable"));
    }

    static PluginRuntimeServices unavailable() {
        return (flowId, targetNodeId, source) -> java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("This plugin manager was created without runtime services"));
    }
}
