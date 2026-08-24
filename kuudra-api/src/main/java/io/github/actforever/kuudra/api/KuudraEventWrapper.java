package io.github.actforever.kuudra.api;

/** Closed execution-domain wrapper used by Runtime routing. */
public sealed interface KuudraEventWrapper permits RawEventWrapper, SessionEventWrapper {
    KuudraEvent event();
    default EventDomain domain() { return this instanceof RawEventWrapper ? EventDomain.RAW : EventDomain.SESSION; }
}
