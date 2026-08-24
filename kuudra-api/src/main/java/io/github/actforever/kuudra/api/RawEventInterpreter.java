package io.github.actforever.kuudra.api;

import java.util.List;

/** Stateful RAW-domain interpretation such as sequence/window recognition. */
@FunctionalInterface
public interface RawEventInterpreter extends Lifecycle {
    List<KuudraEvent> interpret(KuudraEvent event, EventContext context);
}
