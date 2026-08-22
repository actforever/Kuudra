package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.Actor;
import io.github.actforever.kuudra.api.SessionProcessor;

import java.util.List;
import java.util.Objects;

/** Immutable session-stage graph for the minimal runtime: session processor followed by serial Actors. */
public record KuudraFlow(String id, SessionProcessor sessionProcessor, List<Actor> actors) {
    public KuudraFlow {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        Objects.requireNonNull(sessionProcessor, "sessionProcessor");
        actors = List.copyOf(Objects.requireNonNull(actors, "actors"));
    }
}
