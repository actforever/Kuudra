package io.github.actforever.kuudra.api;

import java.util.function.Consumer;

public interface SystemEventBus extends SystemEventPublisher {
    AutoCloseable subscribe(Consumer<SystemEvent> listener);
}
