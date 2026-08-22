package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.SessionPolicy;
import io.github.actforever.kuudra.api.SessionSpec;
import io.github.actforever.kuudra.plugin.DefaultPluginManager;
import io.github.actforever.kuudra.plugin.PluginArchiveLoader;
import io.github.actforever.kuudra.plugin.PluginRuntimeServices;
import io.github.actforever.kuudra.runtime.FlowNode;
import io.github.actforever.kuudra.runtime.IngressPipeline;
import io.github.actforever.kuudra.runtime.KuudraFlow;
import io.github.actforever.kuudra.runtime.KuudraRuntime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Loads the separate HelloWorld plugin archive and proves source registration/unregistration. */
public final class KuudraPluginDemo {
    private KuudraPluginDemo() { }

    public static void main(String[] args) throws Exception {
        Path archive = args.length == 1
                ? Path.of(args[0])
                : Path.of("kuudra-hello-plugin", "target", "kuudra-hello-plugin-0.1.0-SNAPSHOT.jar");
        CountDownLatch threeSignals = new CountDownLatch(3);
        AtomicInteger received = new AtomicInteger();
        try (KuudraRuntime runtime = new KuudraRuntime(64, 2);
             PluginArchiveLoader.LoadedArchive loaded = new PluginArchiveLoader().load(archive, KuudraPluginDemo.class.getClassLoader());
             DefaultPluginManager plugins = new DefaultPluginManager(Path.of("build", "plugin-homes"), runtime::registerSource)) {
            runtime.registerFlow(new KuudraFlow("hello-flow",
                    (raw, context) -> raw.type().equals("demo.hello-world")
                            ? List.of(context.root(raw, new SessionSpec("hello", "periodic", SessionPolicy.PARALLEL))) : List.of(),
                    "print", Map.of("print", new FlowNode.ActorNode("print", (signal, context) -> {
                        System.out.println("Plugin signal: " + signal.raw().payload().get("message"));
                        received.incrementAndGet(); threeSignals.countDown();
                        return CompletableFuture.completedFuture(List.of());
                    })), Map.of()));
            runtime.registerIngress(new IngressPipeline("hello-world-ingress", List.of(),
                    List.of(new IngressPipeline.Output(raw -> raw.type().equals("demo.hello-world"), "hello-flow"))));
            loaded.plugins().forEach(plugins::register);
            plugins.startAll().toCompletableFuture().join();
            if (!threeSignals.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("HelloWorld source did not emit three signals");
            plugins.stopAll().toCompletableFuture().join();
            int stoppedCount = received.get();
            Thread.sleep(250);
            if (received.get() != stoppedCount || !runtime.awaitNoActiveSessions(Duration.ofSeconds(1))) {
                throw new IllegalStateException("Plugin source did not stop cleanly");
            }
        }
        System.out.println("Kuudra plugin demo completed successfully.");
    }
}
