package io.github.actforever.kuudra.api;

import java.util.List;

/** Explicit SESSION-to-RAW boundary. */
@FunctionalInterface
public interface Egress {
    List<KuudraEvent> export(KuudraEvent event, EventContext context);
}
