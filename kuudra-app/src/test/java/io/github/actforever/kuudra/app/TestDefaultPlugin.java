package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.component.IngressDecision;
import io.github.actforever.kuudra.api.context.EventContext;
import io.github.actforever.kuudra.api.event.KuudraEvent;
import io.github.actforever.kuudra.plugin.KuudraPlugin;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
}
