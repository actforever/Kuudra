package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.runtime.IngressPipeline;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runnable in-memory equivalent of the architecture document's "double press A -> tap C" flow.
 * No native input or Robot plugin is used: the Action prints the simulated C tap instead.
 */
public final class DoubleClickDemo {
    private DoubleClickDemo() { }

    public static void main(String[] args) throws Exception {
        CountDownLatch acted = new CountDownLatch(1);
        try (KuudraRuntime runtime = new KuudraRuntime(128, 2)) {
            runtime.registerFlow(new KuudraFlow(
                    "double-a-to-c",
                    (raw, state) -> raw.type().equals("gesture.a.doublePressed")
                            ? Optional.of(new SessionSpec("double-a-action", "A", SessionPolicy.PARALLEL))
                            : Optional.empty(),
                    List.of((signal, context) -> {
                        System.out.printf("Action executed: simulate key C [session=%s]%n", signal.sessionId());
                        acted.countDown();
                        return CompletableFuture.completedFuture(null);
                    })
            ));
            runtime.registerIngress(new IngressPipeline(
                    "demo-keyboard",
                    List.of(new DoublePressProcessor("input.key.pressed", "A", Duration.ofMillis(500))),
                    List.of(new IngressPipeline.Output(raw -> raw.type().equals("gesture.a.doublePressed"), "double-a-to-c"))
            ));

            runtime.publish("demo-keyboard", keyA());
            runtime.publish("demo-keyboard", keyA());
            if (!acted.await(2, TimeUnit.SECONDS) || !runtime.awaitNoActiveSessions(Duration.ofSeconds(2))) {
                throw new IllegalStateException("demo flow did not complete");
            }
        }
        System.out.println("Kuudra demo completed successfully.");
    }

    private static RawSignal keyA() {
        return new RawSignal(java.util.UUID.randomUUID(), "input.key.pressed", Instant.now(), Map.of("key", "A"));
    }

    private static final class DoublePressProcessor implements io.github.actforever.kuudra.api.RawSignalProcessor {
        private final String inputType;
        private final String key;
        private final Duration window;
        private Instant firstPress;

        private DoublePressProcessor(String inputType, String key, Duration window) {
            this.inputType = inputType; this.key = key; this.window = window;
        }

        @Override
        public synchronized List<RawSignal> process(RawSignal raw) {
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
