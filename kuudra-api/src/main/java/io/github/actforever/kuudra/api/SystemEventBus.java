package io.github.actforever.kuudra.api;

import java.util.function.Consumer;

public interface SystemEventBus {
    AutoCloseable subscribe(Consumer<SystemEvent> listener);
    void publish(SystemEvent event);
}
