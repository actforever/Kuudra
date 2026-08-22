package io.github.actforever.kuudra.api;

import java.util.List;

@FunctionalInterface
public interface SessionProcessor {
    List<RootSignal> process(RawSignal signal, SessionProcessorContext context);
}
