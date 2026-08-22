package io.github.actforever.kuudra.api;

@FunctionalInterface
public interface RawSignalEmitter {
    boolean emit(RawSignal signal);
}
