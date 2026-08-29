package io.github.actforever.kuudra.api.context;

/** Control-plane layer currently suspending an invocation. */
public enum SuspensionReason {
    KERNEL,
    ABILITY,
    COMPONENT,
    SESSION
}
