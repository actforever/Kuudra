package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.RootSignal;
import io.github.actforever.kuudra.api.Signal;

import java.util.UUID;

sealed interface RuntimeTask permits RuntimeTask.RawTask, RuntimeTask.RootTask, RuntimeTask.SignalTask {
    record RawTask(String pipelineId, RawSignal signal) implements RuntimeTask { }
    record RootTask(RootSignal signal) implements RuntimeTask { }
    record SignalTask(UUID sessionId, String nodeId, Signal signal) implements RuntimeTask { }
}
