package io.github.actforever.kuudra.api;

/** External producer of unbound Events. */
public interface EventSource extends Lifecycle {
    void setEmitter(EventEmitter emitter);
}
