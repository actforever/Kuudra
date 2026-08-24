package io.github.actforever.kuudra.api;

import java.util.Objects;

/** Event before Ingress admission or after Egress. */
public record RawEventWrapper(KuudraEvent event) implements KuudraEventWrapper {
    public RawEventWrapper { Objects.requireNonNull(event, "event"); }
}
