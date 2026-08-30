package io.github.actforever.kuudra.api.component;

import io.github.actforever.kuudra.api.event.KuudraEvent;

import java.util.List;

/** Runtime-owned event buffer isolated to one Ability revision and Interpreter node. */
public interface EventBuffer {
    void add(KuudraEvent event);
    List<KuudraEvent> snapshot();
    int size();
    void clear();
}
