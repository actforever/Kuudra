package io.github.actforever.kuudra.api;

@FunctionalInterface
public interface RootSignalEmitter {
    boolean emit(RootSignal signal);
}
