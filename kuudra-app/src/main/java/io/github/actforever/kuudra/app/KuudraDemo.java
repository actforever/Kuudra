package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.ActionResult;
import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.config.KuudraConfig;
import io.github.actforever.kuudra.runtime.ActionActor;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.IngressPipeline;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** YAML subset -> RawSignal pipeline -> RootSignal -> Actor/Action end-to-end demo. */
public final class KuudraDemo {
    private KuudraDemo() { }

    public static void main(String[] args) throws Exception {
        KuudraConfig.DemoConfig config;
        try (var stream = KuudraDemo.class.getResourceAsStream("/demo/kuudra-demo.yaml")) {
            if (stream == null) throw new IllegalStateException("demo configuration resource is missing");
            config = KuudraConfig.loadDemo(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        CountDownLatch acted = new CountDownLatch(1);
        var action = (io.github.actforever.kuudra.api.Action) call -> {
            System.out.printf("Action executed: simulate key %s [session=%s]%n", config.simulateKey(), call.context().sessionId());
            acted.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(ActionResult.empty());
        };
        var actor = new ActionActor(List.of(new ActionActor.Binding(signal -> signal.raw().type().equals(config.acceptType()), action, Map.of("key", config.simulateKey()))));
        try (KuudraRuntime runtime = new KuudraRuntime(config.queueCapacity(), config.actorThreads())) {
            runtime.registerFlow(new KuudraFlow(config.flowId(),
                    (raw, context) -> raw.type().equals(config.acceptType())
                            ? List.of(context.root(raw, new SessionSpec(config.sessionName(), config.key(), config.policy()))) : List.of(),
                    "actor", Map.of("actor", new FlowNode.ActorNode("actor", actor)), Map.of()));
            runtime.registerIngress(new IngressPipeline(config.ingressId(),
                    List.of(new DoublePressProcessor(config.inputType(), config.key(), Duration.ofMillis(config.doublePressWindowMs()), config.acceptType())),
                    List.of(new IngressPipeline.Output(raw -> raw.type().equals(config.acceptType()), config.flowId()))));
            runtime.publishRaw(config.ingressId(), key(config));
            runtime.publishRaw(config.ingressId(), key(config));
            if (!acted.await(2, TimeUnit.SECONDS) || !runtime.awaitNoActiveSessions(Duration.ofSeconds(2))) {
                throw new IllegalStateException("demo flow did not complete");
            }
        }
        System.out.println("Kuudra demo completed successfully.");
    }

    private static RawSignal key(KuudraConfig.DemoConfig config) {
        return new RawSignal(UUID.randomUUID(), config.inputType(), Instant.now(), Map.of("key", config.key()));
    }

    private static final class DoublePressProcessor implements io.github.actforever.kuudra.api.RawSignalProcessor {
        private final String inputType, key, outputType;
        private final Duration window;
        private Instant first;
        private DoublePressProcessor(String inputType, String key, Duration window, String outputType) {
            this.inputType = inputType; this.key = key; this.window = window; this.outputType = outputType;
        }
        @Override public synchronized List<RawSignal> process(RawSignal raw) {
            if (!raw.type().equals(inputType) || !key.equals(raw.payload().get("key"))) return List.of(raw);
            if (first != null && !raw.occurredAt().isAfter(first.plus(window))) {
                first = null;
                return List.of(new RawSignal(raw.id(), outputType, raw.occurredAt(), raw.payload()));
            }
            first = raw.occurredAt();
            return List.of();
        }
    }
}
