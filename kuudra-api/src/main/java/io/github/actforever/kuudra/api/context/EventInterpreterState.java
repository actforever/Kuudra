package io.github.actforever.kuudra.api.context;

import java.util.Map;

/** Codec-backed mutable state isolated to one Ability revision and EventInterpreter node. */
public interface EventInterpreterState extends ValueContext {
    default void clear() { update(ignored -> Map.of()); }
}
