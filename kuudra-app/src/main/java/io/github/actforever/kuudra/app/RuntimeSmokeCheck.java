package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.RawSignal;
import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.runtime.IngressPipeline;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Executable zero-dependency runtime smoke check. */
public final class RuntimeSmokeCheck {
    private RuntimeSmokeCheck() { }

    public static void main(String[] args) throws Exception {
        CountDownLatch calls = new CountDownLatch(1);
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            runtime.registerFlow(new KuudraFlow("flow", (raw, state) -> Optional.of(
                    new SessionSpec("single", "key", SessionPolicy.IGNORE)), List.of((signal, context) -> {
                calls.countDown();
                return CompletableFuture.completedFuture(null);
            })));
            runtime.registerIngress(new IngressPipeline("in", List.of(), List.of(
                    new IngressPipeline.Output(raw -> true, "flow"))));
            if (!runtime.publish("in", RawSignal.of("test", Map.of()))) throw new AssertionError("first signal rejected");
            if (!calls.await(1, TimeUnit.SECONDS)) throw new AssertionError("actor was not invoked");
            if (!runtime.awaitNoActiveSessions(Duration.ofSeconds(1))) throw new AssertionError("session did not terminate");
        }
        System.out.println("RuntimeSmokeCheck passed");
    }
}
