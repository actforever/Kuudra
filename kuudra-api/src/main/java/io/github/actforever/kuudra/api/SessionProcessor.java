package io.github.actforever.kuudra.api;

import java.util.Optional;

@FunctionalInterface
public interface SessionProcessor {
    Optional<SessionSpec> process(RawSignal signal, RuntimeStateView state);
}
