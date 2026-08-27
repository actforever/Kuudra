package io.github.actforever.kuudra.api.session;

/** Determines how an active-session selector is resolved when more than one session matches. */
public enum SessionMatchPolicy {
    /** Exactly one active session must match. */
    UNIQUE,
    /** Bind to the most recently activated matching session. */
    LATEST,
    /** Bind to every matching active session. */
    ALL
}
