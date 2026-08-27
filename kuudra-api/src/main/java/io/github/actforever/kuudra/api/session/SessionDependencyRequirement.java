package io.github.actforever.kuudra.api.session;

import java.util.Objects;

/** Declares a required active-session relationship to resolve atomically when a new session starts. */
public record SessionDependencyRequirement(SessionSelector selector, SessionTerminationPolicy terminationPolicy) {
    public SessionDependencyRequirement {
        Objects.requireNonNull(selector, "selector");
        if (terminationPolicy == null) terminationPolicy = SessionTerminationPolicy.CANCEL_DEPENDENT;
    }
}
