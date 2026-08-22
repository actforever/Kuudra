package io.github.actforever.kuudra.api;

/** A lifecycle-managed external source which produces pre-session RawSignal values. */
public interface SignalSource extends Lifecycle {
    void setEmitter(RawSignalEmitter emitter);
}
