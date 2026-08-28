package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.component.IngressDecision;
import io.github.actforever.kuudra.api.context.EventContext;
import io.github.actforever.kuudra.api.event.KuudraEvent;
import io.github.actforever.kuudra.plugin.KuudraPlugin;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestDefaultPlugin implements KuudraPlugin {
    @Override public String id() { return "default"; }
    @Override public CompletionStage<Void> start() { return CompletableFuture.completedFuture(null); }
    @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
    @io.github.actforever.kuudra.plugin.annotation.Ingress("default")
    public static final class TestIngress implements io.github.actforever.kuudra.api.component.Ingress {
        public TestIngress() { }
        @Override public IngressDecision admit(KuudraEvent event, EventContext context) { return IngressDecision.accept(event.type(), event); }
    }
    @io.github.actforever.kuudra.plugin.annotation.Egress("default")
    public static final class TestEgress implements io.github.actforever.kuudra.api.component.Egress {
        public TestEgress() { }
        @Override public List<KuudraEvent> export(KuudraEvent event, EventContext context) { return List.of(event); }
    }
    @io.github.actforever.kuudra.plugin.annotation.EventSource("standalone-source")
    public static final class TestSource implements io.github.actforever.kuudra.api.component.EventSource {
        public TestSource() { }
        @Override public void setEmitter(io.github.actforever.kuudra.api.event.EventEmitter emitter) { }
    }

    @io.github.actforever.kuudra.plugin.annotation.EventHandler("lifecycle-handler")
    public static final class LifecycleHandler implements io.github.actforever.kuudra.api.component.EventHandler,
            io.github.actforever.kuudra.api.lifecycle.Lifecycle {
        private static final AtomicInteger STARTS = new AtomicInteger();
        public LifecycleHandler() { }
        static void reset() { STARTS.set(0); }
        static int starts() { return STARTS.get(); }
        @Override public CompletionStage<Void> start() { STARTS.incrementAndGet(); return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> handle(KuudraEvent event,
                io.github.actforever.kuudra.api.action.ActionContext context) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @io.github.actforever.kuudra.plugin.annotation.EventSource("flaky-source")
    public static final class FlakySource implements io.github.actforever.kuudra.api.component.EventSource {
        private static final AtomicInteger REMAINING_FAILURES = new AtomicInteger();

        public FlakySource() { }
        static void failNextStart() { REMAINING_FAILURES.set(1); }
        @Override public void setEmitter(io.github.actforever.kuudra.api.event.EventEmitter emitter) { }
        @Override public CompletionStage<Void> start() {
            if (REMAINING_FAILURES.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                return CompletableFuture.failedFuture(new IllegalStateException("planned first-start failure"));
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
