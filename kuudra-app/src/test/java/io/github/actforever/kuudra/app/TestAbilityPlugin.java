package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.component.IngressDecision;
import io.github.actforever.kuudra.api.context.EventContext;
import io.github.actforever.kuudra.api.event.EventHandlerContext;
import io.github.actforever.kuudra.api.event.KuudraEvent;
import io.github.actforever.kuudra.plugin.*;
import io.github.actforever.kuudra.plugin.annotation.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestAbilityPlugin implements KuudraPlugin {
    @Override public String id() { return "ability-test"; }
    @Override public CompletionStage<Void> start() { return CompletableFuture.completedFuture(null); }
    @Override public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }

    @Ingress("group-ingress")
    public static final class GroupIngress implements io.github.actforever.kuudra.api.component.Ingress, ResourceLifecycle {
        @Override public IngressDecision admit(KuudraEvent event, EventContext context) {
            return IngressDecision.accept(context.configuration("group", String.class), event);
        }
    }

    @Controller("network-controller")
    @ResourceDoc(purpose = "Test named Controller endpoints")
    public static final class NetworkController implements ResourceLifecycle {
        static final AtomicInteger STARTS = new AtomicInteger();
        static final AtomicInteger DESTROYS = new AtomicInteger();
        static final AtomicInteger CALLS = new AtomicInteger();
        static volatile String alias;
        static void reset() { STARTS.set(0); DESTROYS.set(0); CALLS.set(0); alias = null; }
        @Override public CompletionStage<Void> start() { STARTS.incrementAndGet(); return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> destroy() { DESTROYS.incrementAndGet(); return CompletableFuture.completedFuture(null); }

        @EventHandler(value = "disconnect", purpose = "Disconnect one configured alias",
                arguments = @SpecProperty(path = "alias", type = String.class, required = true,
                        description = "Configured process alias"))
        public CompletionStage<Void> disconnect(KuudraEvent event, EventHandlerContext context) {
            alias = context.arguments().get("alias", String.class); CALLS.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }
}
