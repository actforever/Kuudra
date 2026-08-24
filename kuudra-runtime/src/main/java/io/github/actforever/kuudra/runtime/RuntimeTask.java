package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.KuudraEventWrapper;

sealed interface RuntimeTask permits RuntimeTask.EventTask, RuntimeTask.StopTask {
    record EventTask(String flowId, long flowRevision, String nodeId, KuudraEventWrapper wrapper) implements RuntimeTask { }
    record StopTask() implements RuntimeTask { }
}
