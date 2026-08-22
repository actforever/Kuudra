package io.github.actforever.kuudra.demo.hello;

import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.EventData;
import io.github.actforever.kuudra.api.EventEmitter;
import io.github.actforever.kuudra.api.EventSource;
import io.github.actforever.kuudra.plugin.KuudraPlugin;
import io.github.actforever.kuudra.plugin.PluginContext;
import io.github.actforever.kuudra.plugin.PluginDescriptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Example archive plugin: periodically emits an Event whose data is HelloWorld. */
public final class HelloWorldPlugin implements KuudraPlugin {
    public static final String SIGNAL_TYPE = "demo.hello-world";
    @Override public String id() { return "hello-world-source"; }
    @Override public PluginDescriptor descriptor() { return new PluginDescriptor(id(), List.of()); }

    @Override public CompletionStage<Void> initialize(PluginContext context) { return CompletableFuture.completedFuture(null); }
    @Override public CompletionStage<Void> start() { return CompletableFuture.completedFuture(null); }
    @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }

    @io.github.actforever.kuudra.plugin.annotation.EventSource("loop-emitter")
    public static final class HelloWorldSource implements EventSource {
        private final AtomicBoolean started = new AtomicBoolean();
        private ScheduledExecutorService scheduler;
        private EventEmitter emitter;

        @Override public void setEmitter(EventEmitter emitter) { this.emitter = emitter; }

        @Override
        public CompletionStage<Void> start() {
            if (!started.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "kuudra-hello-source"));
            scheduler.scheduleAtFixedRate(() -> emitter.emit(new Event(UUID.randomUUID(), SIGNAL_TYPE, Instant.now(),
                    EventData.of("hello-world", Map.of("message", "HelloWorld")), io.github.actforever.kuudra.api.EventLineage.origin(), null)), 0, 100, TimeUnit.MILLISECONDS);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> stop() {
            if (scheduler != null) scheduler.shutdownNow();
            return CompletableFuture.completedFuture(null);
        }
    }
}
