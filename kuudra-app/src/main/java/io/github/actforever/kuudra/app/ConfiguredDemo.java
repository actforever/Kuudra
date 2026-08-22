package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.config.KuudraConfig;
import io.github.actforever.kuudra.runtime.IngressPipeline;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Demonstrates YAML-subset configuration -> graph assembly -> runtime execution. */
public final class ConfiguredDemo {
    private ConfiguredDemo() { }

    public static void main(String[] args) throws Exception {
        KuudraConfig.DemoConfig config;
        try (var stream = ConfiguredDemo.class.getResourceAsStream("/demo/kuudra-demo.yaml")) {
            if (stream == null) throw new IllegalStateException("demo configuration resource is missing");
            config = KuudraConfig.loadDemo(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        CountDownLatch acted = new CountDownLatch(1);
        try (KuudraRuntime runtime = new KuudraRuntime(config.queueCapacity(), config.actorThreads())) {
            runtime.registerFlow(new KuudraFlow(config.flowId(), (raw, state) ->
                    raw.type().equals(config.acceptType())
                            ? Optional.of(new io.github.actforever.kuudra.api.SessionSpec(
                                    config.sessionName(), config.key(), config.policy()))
                            : Optional.empty(),
                    List.of((signal, context) -> {
                        System.out.printf("Configured action executed: simulate key %s [session=%s]%n",
                                config.simulateKey(), signal.sessionId());
                        acted.countDown();
                        return CompletableFuture.completedFuture(null);
                    })
            ));
            runtime.registerIngress(new IngressPipeline(config.ingressId(),
                    List.of(new DoublePressProcessor(config.inputType(), config.key(), Duration.ofMillis(config.doublePressWindowMs()))),
                    List.of(new IngressPipeline.Output(raw -> raw.type().equals(config.acceptType()), config.flowId()))));
            runtime.publish(config.ingressId(), key(config));
            runtime.publish(config.ingressId(), key(config));
            if (!acted.await(2, TimeUnit.SECONDS) || !runtime.awaitNoActiveSessions(Duration.ofSeconds(2))) {
                throw new IllegalStateException("configured demo did not complete");
            }
        }
        System.out.println("Configured Kuudra demo completed successfully.");
    }

    private static RawSignal key(KuudraConfig.DemoConfig config) {
        return new RawSignal(UUID.randomUUID(), config.inputType(), Instant.now(), Map.of("key", config.key()));
    }

    private static final class DoublePressProcessor implements io.github.actforever.kuudra.api.RawSignalProcessor {
        private final String inputType;
        private final String key;
        private final Duration window;
        private Instant firstPress;

        private DoublePressProcessor(String inputType, String key, Duration window) {
            this.inputType = inputType; this.key = key; this.window = window;
        }

        @Override public synchronized List<RawSignal> process(RawSignal raw) {
            if (!raw.type().equals(inputType) || !key.equals(raw.payload().get("key"))) return List.of(raw);
            if (firstPress != null && !raw.occurredAt().isAfter(firstPress.plus(window))) {
                firstPress = null;
                return List.of(new RawSignal(raw.id(), "gesture.a.doublePressed", raw.occurredAt(), raw.payload()));
            }
            firstPress = raw.occurredAt();
            return List.of();
        }
    }
}
