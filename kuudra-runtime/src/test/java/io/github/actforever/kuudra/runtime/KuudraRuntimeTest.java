package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class KuudraRuntimeTest {
    @Test
    void rawIngressSessionHandlerAndEgressFormClosedPipeline() throws Exception {
        CountDownLatch handled = new CountDownLatch(1); CountDownLatch exported = new CountDownLatch(1);
        AtomicReference<UUID> sessionId = new AtomicReference<>(); AtomicReference<Set<UUID>> lineage = new AtomicReference<>();
        IngressConfiguration scheduling = new IngressConfiguration(SessionSchedulingPolicy.PARALLEL, SessionGroupScope.FLOW_BINDING, 4, 4);
        try (KuudraRuntime runtime = new KuudraRuntime(32, 2)) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of(
                    "interpret", new FlowNode.InterpreterNode("interpret", (event, context) -> List.of(event.retype("interpreted")), Map.of()),
                    "ingress", new FlowNode.IngressNode("ingress", (event, context) -> IngressDecision.accept(event.type(), event), scheduling, Map.of()),
                    "handler", new FlowNode.HandlerNode("handler", (event, context) -> { sessionId.set(context.sessionId()); handled.countDown(); context.emit(event.retype("done")); return CompletableFuture.completedFuture(null); }, Map.of()),
                    "egress", new FlowNode.EgressNode("egress", (event, context) -> List.of(event), Map.of()),
                    "raw", new FlowNode.AdapterNode("raw", (event, context) -> { lineage.set(event.lineage().parentSessionIds()); exported.countDown(); return List.of(); }, EventDomain.RAW)
            ), Map.of("interpret",List.of("ingress"),"ingress",List.of("handler"),"handler",List.of("egress"),"egress",List.of("raw"))));
            assertTrue(runtime.publish("flow","interpret",KuudraEvent.of("input",EventData.empty())));
            assertTrue(handled.await(1,TimeUnit.SECONDS)); assertTrue(exported.await(1,TimeUnit.SECONDS));
            assertTrue(lineage.get().contains(sessionId.get())); assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
        }
    }

    @Test
    void flowRejectsIllegalDomainEdgesAndRawSessionPlaceholders() {
        FlowNode raw = new FlowNode.AdapterNode("raw", (event, context) -> List.of(event), EventDomain.RAW, Map.of("bad","${session#x}"));
        FlowNode handler = new FlowNode.HandlerNode("handler", (event, context) -> CompletableFuture.completedFuture(null), Map.of());
        assertThrows(KuudraException.class, () -> new KuudraFlow("bad",Map.of("raw",raw,"handler",handler),Map.of("raw",List.of("handler"))));
        try (KuudraRuntime runtime = new KuudraRuntime(8,1)) {
            assertThrows(KuudraException.class, () -> runtime.registerFlow(new KuudraFlow("bad-placeholder",Map.of("raw",raw),Map.of())));
        }
    }

    @Test
    void serialPolicyDefersSecondAdmissionUntilFirstLeaseCompletes() throws Exception {
        CountDownLatch firstStarted=new CountDownLatch(1);CountDownLatch release=new CountDownLatch(1);CountDownLatch both=new CountDownLatch(2);
        IngressConfiguration serial=new IngressConfiguration(SessionSchedulingPolicy.SERIAL,SessionGroupScope.FLOW_BINDING,1,4);
        try(KuudraRuntime runtime=new KuudraRuntime(16,2)){
            runtime.registerFlow(new KuudraFlow("flow",Map.of(
                    "in",new FlowNode.IngressNode("in",(event,context)->IngressDecision.accept("same",event),serial,Map.of()),
                    "handler",new FlowNode.HandlerNode("handler",(event,context)->CompletableFuture.runAsync(()->{firstStarted.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}both.countDown();}),Map.of())
            ),Map.of("in",List.of("handler"))));
            runtime.publish("flow","in",KuudraEvent.of("one",Map.of()));runtime.publish("flow","in",KuudraEvent.of("two",Map.of()));
            assertTrue(firstStarted.await(1,TimeUnit.SECONDS)); assertEquals(1,runtime.sessions().snapshots().stream().filter(s->s.status()==SessionStatus.ACTIVE).count());
            release.countDown();assertTrue(both.await(2,TimeUnit.SECONDS));
        }
    }

    @Test
    void sourceCanOnlyBindRawNode() {
        EventSource source=new EventSource(){public void setEmitter(EventEmitter emitter){}public CompletionStage<Void> start(){return CompletableFuture.completedFuture(null);}public CompletionStage<Void> stop(){return CompletableFuture.completedFuture(null);}};
        try(KuudraRuntime runtime=new KuudraRuntime(8,1)){
            runtime.registerFlow(new KuudraFlow("flow",Map.of("handler",new FlowNode.HandlerNode("handler",(e,c)->CompletableFuture.completedFuture(null),Map.of())),Map.of()));
            assertThrows(KuudraException.class,()->runtime.registerSource("flow","handler",source));
        }
    }
}
