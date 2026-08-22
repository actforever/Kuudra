package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.RawSignalProcessor;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Runtime-level pre-session pipeline. It owns no sessions. */
public record IngressPipeline(String id, List<RawSignalProcessor> processors, List<Output> outputs) {
    public IngressPipeline {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        processors = List.copyOf(Objects.requireNonNull(processors, "processors"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
    }

    public record Output(Predicate<RawSignal> selector, String flowId) {
        public Output {
            Objects.requireNonNull(selector, "selector");
            if (flowId == null || flowId.isBlank()) throw new IllegalArgumentException("flowId must not be blank");
        }
    }
}
