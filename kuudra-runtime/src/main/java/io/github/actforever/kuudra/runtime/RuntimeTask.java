package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.Event;

sealed interface RuntimeTask permits RuntimeTask.EventTask {
    record EventTask(String flowId, String nodeId, Event event) implements RuntimeTask { }
}
