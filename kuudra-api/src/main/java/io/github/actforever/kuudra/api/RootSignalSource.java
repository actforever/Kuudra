package io.github.actforever.kuudra.api;

/** A lifecycle-managed source that bypasses raw processing and directly requests sessions. */
public interface RootSignalSource extends Lifecycle {
    void setEmitter(RootSignalEmitter emitter);
}
