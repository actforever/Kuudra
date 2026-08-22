package io.github.actforever.kuudra.api;

import java.util.List;

@FunctionalInterface
public interface RawSignalProcessor {
    List<RawSignal> process(RawSignal signal);
}
