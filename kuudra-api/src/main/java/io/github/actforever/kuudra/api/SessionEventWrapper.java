package io.github.actforever.kuudra.api;

import java.util.Objects;

/** Event admitted to exactly one Runtime-owned Session. */
public record SessionEventWrapper(KuudraEvent event, SessionReference session) implements KuudraEventWrapper {
    public SessionEventWrapper {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(session, "session");
    }
}
