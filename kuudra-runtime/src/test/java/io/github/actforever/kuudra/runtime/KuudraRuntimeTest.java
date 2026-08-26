package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.*;
import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class KuudraRuntimeTest {
    @Test
    void cooperativeCheckpointParksCurrentHandlerWithoutChangingSessionState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger continued = new AtomicInteger();
        EventHandler handler = (event, context) -> {
            entered.countDown();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                while (!context.executionControl().isPauseRequested()) Thread.onSpinWait();
                context.executionControl().checkpoint().whenComplete((decision, error) -> {
                    if (error != null) completion.completeExceptionally(error);
                    else {
                        if (decision == ExecutionDecision.CONTINUE) continued.incrementAndGet();
                        completion.complete(null);
                    }
                });
            });
            return completion;
        };
        IngressConfiguration scheduling = new IngressConfiguration(
                SessionSchedulingPolicy.PARALLEL, SessionGroupScope.FLOW_BINDING, 1, 1);
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            runtime.registerFlow(new KuudraFlow("cooperative", Map.of(
                    "ingress", new FlowNode.IngressNode("ingress", (event, context) ->
                            IngressDecision.accept("group", event), scheduling, Map.of()),
                    "handler", new FlowNode.HandlerNode("handler", handler, Map.of())),
                    Map.of("ingress", List.of("handler"))));
            assertTrue(runtime.publish("cooperative", "ingress", KuudraEvent.of("work", Map.of())));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            RuntimeCheckpoint checkpoint = CompletableFuture.supplyAsync(runtime::pause).get(1, TimeUnit.SECONDS);
            assertEquals(0, continued.get());
            assertTrue(checkpoint.sessions().stream().anyMatch(session -> session.status() == SessionStatus.ACTIVE));

            runtime.resume();
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
            assertEquals(1, continued.get());
        }
    }

    @Test
    void reconcilerGatePreventsAStoppedLifecycleComponentFromReceivingEvents() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        EventHandler handler = (event, context) -> {
            handled.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        };
        IngressConfiguration scheduling = new IngressConfiguration(
                SessionSchedulingPolicy.PARALLEL, SessionGroupScope.FLOW_BINDING, 1, 1);
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            runtime.registerFlow(new KuudraFlow("gated", Map.of(
                    "ingress", new FlowNode.IngressNode("ingress", (event, context) ->
                            IngressDecision.accept("group", event), scheduling, Map.of()),
                    "handler", new FlowNode.HandlerNode("handler", handler, Map.of())),
                    Map.of("ingress", List.of("handler"))));
            runtime.setComponentEnabled(handler, false);
            assertTrue(runtime.publish("gated", "ingress", KuudraEvent.of("ignored", Map.of())));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
            assertEquals(0, handled.get());
        }
    }

    @Test
    void reconcilerGateAlsoCoversPassiveIngressComponents() throws Exception {
        AtomicInteger admissions = new AtomicInteger();
        Ingress ingress = (event, context) -> {
            admissions.incrementAndGet();
            return IngressDecision.reject("test");
        };
        IngressConfiguration scheduling = new IngressConfiguration(
                SessionSchedulingPolicy.PARALLEL, SessionGroupScope.FLOW_BINDING, 1, 1);
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            runtime.registerFlow(new KuudraFlow("inactive", Map.of(
                    "ingress", new FlowNode.IngressNode("ingress", ingress, scheduling, Map.of())), Map.of()));
            runtime.setComponentEnabled(ingress, false);
            assertTrue(runtime.publish("inactive", "ingress", KuudraEvent.of("ignored", Map.of())));
            Thread.sleep(100);
            assertEquals(0, admissions.get());
        }
    }

    @Test
    void publishesStructuredDebugEventsForTheTaskExecutionPath() throws Exception {
        CopyOnWriteArrayList<SystemEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch handled = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1, Map.of(), 32, event -> {
            events.add(event);
            if (event.type().equals("runtime.node.execution.completed")) completed.countDown();
        })) {
            runtime.registerFlow(new KuudraFlow("observed", Map.of("sink", new FlowNode.AdapterNode(
                    "sink", (event, context) -> { handled.countDown(); return List.of(); }, EventDomain.RAW)), Map.of()));
            assertTrue(runtime.publish("observed", "sink", KuudraEvent.of("diagnostic", Map.of())));
            assertTrue(handled.await(1, TimeUnit.SECONDS));
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertTrue(events.stream().anyMatch(event -> event.type().equals("runtime.event.enqueued")
                    && event.level() == SystemEventLevel.DEBUG));
            assertTrue(events.stream().anyMatch(event -> event.type().equals("runtime.node.execution.completed")
                    && event.level() == SystemEventLevel.DEBUG));
        }
    }

    @Test
    void reportsGracefulShutdownWaitBoundaries() {
        CopyOnWriteArrayList<SystemEvent> events = new CopyOnWriteArrayList<>();
        KuudraRuntime runtime = new KuudraRuntime(8, 1, Map.of(), 32, events::add);
        runtime.close();
        assertTrue(events.stream().filter(event -> event.type().startsWith("runtime.shutdown."))
                .allMatch(event -> event.level() == SystemEventLevel.DEBUG));
        assertTrue(events.stream().anyMatch(event -> event.type().equals("runtime.shutdown.started")));
        assertTrue(events.stream().anyMatch(event -> event.type().equals("runtime.shutdown.sessions.draining")));
        assertTrue(events.stream().anyMatch(event -> event.type().equals("runtime.shutdown.sessions.drain.completed")));
        assertTrue(events.stream().anyMatch(event -> event.type().equals("runtime.shutdown.completed")));
    }

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
    void ingressScopeUsesStableComponentIdentityAcrossFlows() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bothCompleted = new CountDownLatch(2);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Ingress ingress = (event, context) -> IngressDecision.accept("same", event);
        IngressConfiguration serial = new IngressConfiguration(SessionSchedulingPolicy.SERIAL,
                SessionGroupScope.INGRESS, 1, 4);
        EventHandler handler = (event, context) -> CompletableFuture.runAsync(() -> {
            int current = running.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            firstStarted.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            running.decrementAndGet();
            bothCompleted.countDown();
        });
        try (KuudraRuntime runtime = new KuudraRuntime(16, 2)) {
            runtime.registerFlow(new KuudraFlow("flow-a", Map.of(
                    "entry-a", new FlowNode.IngressNode("entry-a", "demo/shared-ingress", ingress, serial, Map.of()),
                    "handler-a", new FlowNode.HandlerNode("handler-a", handler, Map.of())
            ), Map.of("entry-a", List.of("handler-a"))));
            runtime.registerFlow(new KuudraFlow("flow-b", Map.of(
                    "entry-b", new FlowNode.IngressNode("entry-b", "demo/shared-ingress", ingress, serial, Map.of()),
                    "handler-b", new FlowNode.HandlerNode("handler-b", handler, Map.of())
            ), Map.of("entry-b", List.of("handler-b"))));
            assertTrue(runtime.publish("flow-a", "entry-a", KuudraEvent.of("one", Map.of())));
            assertTrue(runtime.publish("flow-b", "entry-b", KuudraEvent.of("two", Map.of())));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals(1, peak.get());
            release.countDown();
            assertTrue(bothCompleted.await(2, TimeUnit.SECONDS));
            assertEquals(1, peak.get());
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

    @Test
    void configuredHopLimitRejectsBeforeQueueing() {
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1, Map.of(), 1)) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of("raw", new FlowNode.AdapterNode(
                    "raw", (event, context) -> List.of(event), EventDomain.RAW)), Map.of()));
            KuudraEvent exhausted = new KuudraEvent(UUID.randomUUID(), "input", java.time.Instant.now(),
                    EventData.empty(), new EventLineage(Set.of(), Set.of(), 1));
            assertFalse(runtime.publish("flow", "raw", exhausted));
            assertEquals(0, runtime.queuedTasks());
        }
    }

    @Test
    void failedSessionDrainsEveryLeaseBeforePublishingTerminalState() throws Exception {
        AtomicReference<UUID> sessionId = new AtomicReference<>();
        CountDownLatch created = new CountDownLatch(1);
        IngressConfiguration scheduling = new IngressConfiguration(SessionSchedulingPolicy.PARALLEL, SessionGroupScope.FLOW_BINDING, 2, 2);
        try (KuudraRuntime runtime = new KuudraRuntime(16, 1)) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of(
                    "in", new FlowNode.IngressNode("in", (event, context) -> IngressDecision.accept("group", event), scheduling, Map.of()),
                    "emit", new FlowNode.HandlerNode("emit", (event, context) -> { sessionId.set(context.sessionId()); created.countDown(); context.emit(event); return CompletableFuture.completedFuture(null); }, Map.of()),
                    "fail", new FlowNode.HandlerNode("fail", (event, context) -> CompletableFuture.failedFuture(new IllegalStateException("boom")), Map.of()),
                    "skipped", new FlowNode.HandlerNode("skipped", (event, context) -> CompletableFuture.completedFuture(null), Map.of())
            ), Map.of("in", List.of("emit"), "emit", List.of("fail", "skipped"))));
            runtime.publish("flow", "in", KuudraEvent.of("input", Map.of()));
            assertTrue(created.await(1, TimeUnit.SECONDS));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
            SessionSnapshot snapshot = runtime.session(sessionId.get()).orElseThrow();
            assertEquals(SessionStatus.FAILED, snapshot.status());
            assertEquals(0, snapshot.activeLeases());
        }
    }

    @Test
    void replacementPolicyQueuesBeforeSynchronousCancellationCallback() {
        SessionCoordinator coordinator = new SessionCoordinator();
        SessionCoordinator.Group group = new SessionCoordinator.Group("scope", "ingress", "key");
        IngressConfiguration configuration = new IngressConfiguration(SessionSchedulingPolicy.CANCEL_AND_REPLACE_PENDING,
                SessionGroupScope.FLOW_BINDING, 1, 1);
        AtomicReference<UUID> active = new AtomicReference<>();
        AtomicInteger launches = new AtomicInteger();
        Runnable launch = () -> { UUID id = UUID.randomUUID(); active.set(id); launches.incrementAndGet(); coordinator.activated(group, id); };
        assertTrue(coordinator.admit(group, configuration, launch, ignored -> { }));
        assertTrue(coordinator.admit(group, configuration, launch, id -> coordinator.terminal(group, id)));
        assertEquals(2, launches.get());
        assertNotNull(active.get());
    }
}
