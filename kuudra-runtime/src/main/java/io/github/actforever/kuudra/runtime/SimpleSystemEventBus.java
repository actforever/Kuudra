package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class SimpleSystemEventBus implements SystemEventBus {
    private final CopyOnWriteArrayList<Consumer<SystemEvent>> listeners = new CopyOnWriteArrayList<>();
    @Override public AutoCloseable subscribe(Consumer<SystemEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    @Override public void publish(SystemEvent event) {
        for (Consumer<SystemEvent> listener : listeners) {
            try { listener.accept(event); } catch (RuntimeException ignored) { }
        }
    }
}
