package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.KuudraFlow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Loads the separate EventSource plugin archive and proves Flow-node binding. */
public final class KuudraPluginDemo {
    private KuudraPluginDemo() { }
    public static void main(String[] args) throws Exception {
        KuudraBanner.print();
        Path archive = args.length == 1 ? Path.of(args[0]) : Path.of("kuudra-hello-plugin", "target", "kuudra-hello-plugin-0.1.0-SNAPSHOT.jar");
        CountDownLatch threeSignals = new CountDownLatch(3);
        try (KuudraApp app = new KuudraApp(64, 2)) {
            app.registerFlow(new KuudraFlow("hello-flow", Map.of(
                    "allocate", new FlowNode.AllocatorNode("allocate", new SessionSpec("hello", "periodic", SessionPolicy.PARALLEL)),
                    "print", new FlowNode.ActorNode("print", (event, context) -> {
                        System.out.println("Plugin event: " + event.data().require("hello-world", "message")); threeSignals.countDown();
                        return CompletableFuture.completedFuture(List.of()); })
            ), Map.of("allocate", List.of("print"))));
            app.loadPlugin(archive);
            app.installEventSource("event-source/hello-world/loop-emitter", "hello-flow", "allocate").toCompletableFuture().join();
            app.startPlugins().toCompletableFuture().join();
            if (!threeSignals.await(2, TimeUnit.SECONDS) || !app.awaitNoActiveSessions(Duration.ofSeconds(2))) throw new IllegalStateException("HelloWorld source did not emit three events");
        }
        System.out.println("Kuudra plugin demo completed successfully.");
    }
}
