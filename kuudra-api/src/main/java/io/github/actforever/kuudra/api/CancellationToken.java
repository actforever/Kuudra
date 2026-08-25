package io.github.actforever.kuudra.api;

public interface CancellationToken {
    boolean isCancellationRequested();

    /** Cooperative pause signal for long-running or asynchronous component work. */
    default boolean isPauseRequested() { return false; }

    /** Completes when execution may continue; callers must not block Runtime worker threads. */
    default java.util.concurrent.CompletionStage<Void> awaitResumed() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
