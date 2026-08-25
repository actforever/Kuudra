package io.github.actforever.kuudra.api;

/** Narrow, write-only port used by kernel modules to publish structured observability events. */
@FunctionalInterface
public interface SystemEventPublisher {
    void publish(SystemEvent event);

    static SystemEventPublisher noop() { return ignored -> { }; }
}
