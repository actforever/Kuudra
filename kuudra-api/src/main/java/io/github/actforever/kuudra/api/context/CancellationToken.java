package io.github.actforever.kuudra.api.context;

/** @deprecated use {@link ExecutionControl}; retained as a source migration bridge. */
@Deprecated(forRemoval = false)
public interface CancellationToken extends ExecutionControl {
    boolean isCancellationRequested();

    /** Cooperative pause signal for long-running or asynchronous component work. */
    default boolean isPauseRequested() { return false; }

    /** Completes when execution may continue; callers must not block Runtime worker threads. */
    default java.util.concurrent.CompletionStage<Void> awaitResumed() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    @Override default ExecutionDecision poll() {
        return isCancellationRequested() ? ExecutionDecision.CANCEL
                : isPauseRequested() ? ExecutionDecision.PAUSE : ExecutionDecision.CONTINUE;
    }

    @Override default java.util.Set<SuspensionReason> suspensionReasons() { return java.util.Set.of(); }

    @Override default java.util.concurrent.CompletionStage<ExecutionDecision> checkpoint() {
        if (isCancellationRequested()) return java.util.concurrent.CompletableFuture.completedFuture(ExecutionDecision.CANCEL);
        return awaitResumed().thenApply(ignored -> isCancellationRequested()
                ? ExecutionDecision.CANCEL : ExecutionDecision.CONTINUE);
    }
}
