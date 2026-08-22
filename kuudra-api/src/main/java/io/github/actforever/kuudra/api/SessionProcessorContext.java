package io.github.actforever.kuudra.api;

import java.util.Objects;

/** Context for the sole RawSignal -> RootSignal bridge. */
public record SessionProcessorContext(String flowId, RuntimeStateView runtimeState) {
    public SessionProcessorContext {
        if (flowId == null || flowId.isBlank()) throw new IllegalArgumentException("flowId must not be blank");
        Objects.requireNonNull(runtimeState, "runtimeState");
    }

    public RootSignal root(RawSignal raw, SessionSpec spec) {
        return RootSignal.of(raw, flowId, spec);
    }
}
