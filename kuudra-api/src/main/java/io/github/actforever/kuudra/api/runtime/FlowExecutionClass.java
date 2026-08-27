package io.github.actforever.kuudra.api.runtime;

/**
 * Selects which kernel execution plane owns a Flow.
 * DATA Flows are suspended by a kernel pause; CONTROL Flows remain available to resume or stop it.
 */
public enum FlowExecutionClass {
    DATA,
    CONTROL
}
