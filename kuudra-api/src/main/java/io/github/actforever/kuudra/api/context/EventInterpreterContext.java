package io.github.actforever.kuudra.api.context;

import io.github.actforever.kuudra.api.component.EventBuffer;
import io.github.actforever.kuudra.api.event.KuudraEvent;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * Durable, Runtime-owned context for one EventInterpreter node binding.
 * Scheduled callbacks and emitters remain valid until the binding is reset, paused, disabled or unregistered.
 */
public interface EventInterpreterContext {
    String abilityId();
    long abilityRevision();
    String nodeId();
    EventContext eventContext();
    EventInterpreterState state();
    EventBuffer buffer(String name);

    /** Replaces any pending task with the same non-blank key. */
    void schedule(String key, Duration delay, Runnable callback);

    /** Cancels a pending named task, returning whether one was cancelled. */
    boolean cancelScheduled(String key);

    /** Emits an Event causally derived from the current input Event. */
    boolean emit(KuudraEvent event);

    /** Emits an Event causally derived from every supplied input Event. */
    boolean emit(KuudraEvent event, Collection<KuudraEvent> causes);

    default ExecutionControl executionControl() { return eventContext().executionControl(); }
    default Map<String, Object> arguments() { return eventContext().configuration(); }
    default TypedValueMap argumentValues() { return eventContext().configurationValues(); }
    default <T> T argument(String key, Class<T> type) { return eventContext().configuration(key, type); }
    default <T> T argument(String key, Class<T> type, T fallback) {
        return eventContext().configuration(key, type, fallback);
    }
}
