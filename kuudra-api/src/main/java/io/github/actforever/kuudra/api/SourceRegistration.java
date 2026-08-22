package io.github.actforever.kuudra.api;

import java.util.concurrent.CompletionStage;

/** Handle returned when a source has been attached to a running KuudraRuntime. */
@FunctionalInterface
public interface SourceRegistration {
    /** Stops the source and removes it from the runtime. This operation is idempotent. */
    CompletionStage<Void> unregister();
}
