package io.github.actforever.kuudra.defaultplugin;

import io.github.actforever.kuudra.api.component.Egress;
import io.github.actforever.kuudra.api.context.EventContext;
import io.github.actforever.kuudra.api.event.KuudraEvent;

import java.util.List;

/** Official pass-through SESSION-to-RAW boundary. */
public final class DefaultEgress implements Egress {
    @Override public List<KuudraEvent> export(KuudraEvent event, EventContext context) { return List.of(event); }
}
