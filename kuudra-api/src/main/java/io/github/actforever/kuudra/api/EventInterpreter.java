package io.github.actforever.kuudra.api;

import java.util.List;

/** Stateful event interpretation such as sequence/window recognition before session admission. */
@FunctionalInterface
public interface EventInterpreter extends Lifecycle {
    List<KuudraEvent> interpret(KuudraEvent event, EventContext context);
}
