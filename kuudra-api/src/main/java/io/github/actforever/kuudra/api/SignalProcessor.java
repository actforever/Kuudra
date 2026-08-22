package io.github.actforever.kuudra.api;

import java.util.List;

/** Stateful session-stage transform; it receives only one session's signals for one processor partition. */
@FunctionalInterface
public interface SignalProcessor {
    List<Signal> process(Signal signal, SignalContext context);
}
