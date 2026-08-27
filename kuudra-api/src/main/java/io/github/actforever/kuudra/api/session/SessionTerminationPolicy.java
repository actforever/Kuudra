package io.github.actforever.kuudra.api.session;

/** Terminal-state propagation for one dependent -> required session edge. */
public enum SessionTerminationPolicy {
    /** Terminating the required session cancels the dependent session. */
    CANCEL_DEPENDENT,
    /** Terminating the dependent session cancels the required session. */
    CANCEL_REQUIRED,
    /** Terminating either session cancels the other session. */
    CANCEL_BOTH
}
