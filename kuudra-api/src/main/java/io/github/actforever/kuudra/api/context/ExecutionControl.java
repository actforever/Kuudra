package io.github.actforever.kuudra.api.context;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Cooperative execution control shared by every component invocation.
 * Synchronous components should only call {@link #poll()}; asynchronous or long-running components may await
 * {@link #checkpoint()} without occupying a Runtime execution slot while suspended.
 */
@FunctionalInterface
public interface ExecutionControl {
    ExecutionDecision poll();

    default Set<SuspensionReason> suspensionReasons() { return Set.of(); }

    default CompletionStage<ExecutionDecision> checkpoint() {
        return java.util.concurrent.CompletableFuture.completedFuture(poll());
    }

    default boolean isCancellationRequested() { return poll() == ExecutionDecision.CANCEL; }

    default boolean isPauseRequested() { return poll() == ExecutionDecision.PAUSE; }
}
