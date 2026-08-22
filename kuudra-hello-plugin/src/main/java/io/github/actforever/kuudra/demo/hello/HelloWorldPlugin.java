package io.github.actforever.kuudra.demo.hello;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.RawSignalEmitter;
import io.github.actforever.kuudra.api.RawSignalSource;
import io.github.actforever.kuudra.api.SourceRegistration;
import io.github.actforever.kuudra.api.SignalData;
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

/** Example archive plugin: periodically emits a RawSignal whose payload is HelloWorld. */
public final class HelloWorldPlugin implements KuudraPlugin {
    public static final String INGRESS_ID = "hello-world-ingress";
    public static final String SIGNAL_TYPE = "demo.hello-world";
    private PluginContext context;
    private SourceRegistration registration;

    @Override public String id() { return "hello-world-source"; }
    @Override public PluginDescriptor descriptor() { return new PluginDescriptor(id(), List.of()); }

    @Override
    public CompletionStage<Void> initialize(PluginContext context) {
        this.context = context;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> start() {
        if (context == null) return CompletableFuture.failedFuture(new IllegalStateException("Plugin was not initialized"));
        return context.runtime().registerRawSource(INGRESS_ID, new HelloWorldSource()).thenAccept(handle -> registration = handle);
    }

    @Override
    public CompletionStage<Void> stop() {
        return registration == null ? CompletableFuture.completedFuture(null) : registration.unregister();
    }

    @io.github.actforever.kuudra.plugin.annotation.SignalSource("loop-emitter")
    public static final class HelloWorldSource implements RawSignalSource {
        private final AtomicBoolean started = new AtomicBoolean();
        private ScheduledExecutorService scheduler;
        private RawSignalEmitter emitter;

        @Override public void setEmitter(RawSignalEmitter emitter) { this.emitter = emitter; }

        @Override
        public CompletionStage<Void> start() {
            if (!started.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "kuudra-hello-source"));
            scheduler.scheduleAtFixedRate(() -> emitter.emit(new RawSignal(UUID.randomUUID(), SIGNAL_TYPE, Instant.now(),
                    SignalData.of("hello-world-source", Map.of("message", "HelloWorld")))), 0, 100, TimeUnit.MILLISECONDS);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> stop() {
            if (scheduler != null) scheduler.shutdownNow();
            return CompletableFuture.completedFuture(null);
        }
    }
}
