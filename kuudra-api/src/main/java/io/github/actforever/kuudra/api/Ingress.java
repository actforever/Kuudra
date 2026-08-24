package io.github.actforever.kuudra.api;

/** RAW-domain admission decision. Session creation and scheduling remain Runtime-owned. */
@FunctionalInterface
public interface Ingress {
    IngressDecision admit(KuudraEvent event, EventContext context);
}
