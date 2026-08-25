package io.github.actforever.kuudra.api.context;

/** Result of checking whether the current component invocation may continue. */
public enum ExecutionDecision {
    CONTINUE,
    PAUSE,
    CANCEL
}
