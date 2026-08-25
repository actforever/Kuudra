package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** App-owned fan-out bus. Kernel modules receive only its write-only publisher contract. */
final class AppSystemEventBus implements SystemEventBus {
    private final CopyOnWriteArrayList<Consumer<SystemEvent>> listeners = new CopyOnWriteArrayList<>();

    @Override public AutoCloseable subscribe(Consumer<SystemEvent> listener) {
        Consumer<SystemEvent> required = Objects.requireNonNull(listener, "listener");
        listeners.add(required);
        return () -> listeners.remove(required);
    }

    @Override public void publish(SystemEvent event) {
        Objects.requireNonNull(event, "event");
        for (Consumer<SystemEvent> listener : listeners) {
            try { listener.accept(event); } catch (RuntimeException ignored) { }
        }
    }
}
