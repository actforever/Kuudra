package io.github.actforever.kuudra.api;

import java.util.List;

/** Stateless session-stage transform. It must preserve the source session id. */
@FunctionalInterface
public interface SignalAdapter {
    List<Signal> adapt(Signal signal, SignalContext context);
}
