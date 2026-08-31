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
    void interpreterMayEmitAfterInvocationAndMergeEveryBufferedCause() throws Exception {
        CountDownLatch emitted = new CountDownLatch(1);
        AtomicReference<KuudraEvent> result = new AtomicReference<>();
        EventInterpreter interpreter = (event, context) -> {
            EventBuffer buffer = context.buffer("window");
            buffer.add(event);
            if (buffer.size() == 1) context.schedule("expire", Duration.ofMillis(40), () -> {
                List<KuudraEvent> causes = buffer.snapshot();
                context.emit(KuudraEvent.of("gesture", EventData.empty()), causes);
                buffer.clear();
            });
        };
        try (KuudraRuntime runtime = new KuudraRuntime(16, 2)) {
            runtime.registerFlow(new KuudraFlow("window", Map.of(
                    "interpreter", new FlowNode.InterpreterNode("interpreter", interpreter, Map.of()),
                    "sink", new FlowNode.AdapterNode("sink", (event, context) -> {
                        result.set(event); emitted.countDown(); return List.of();
                    }, EventDomain.RAW)), Map.of("interpreter", List.of("sink"))));
            KuudraEvent first = KuudraEvent.of("click", EventData.empty());
            KuudraEvent second = KuudraEvent.of("click", EventData.empty());
            assertTrue(runtime.publish("window", "interpreter", first));
            assertTrue(runtime.publish("window", "interpreter", second));
            assertTrue(emitted.await(1, TimeUnit.SECONDS));
            assertEquals(Set.of(first.id(), second.id()), result.get().lineage().parentEventIds());
            assertEquals(1, result.get().lineage().hops());
        }
    }

    @Test
    void sharedInterpreterResourceKeepsStateIsolatedPerAbilityNode() throws Exception {
        EventInterpreter shared = (event, context) -> {
            int count = (context.state().find("count").isPresent()
                    ? context.state().get("count", Integer.class) : 0) + 1;
            context.state().put("count", count);
            if (count == 2) context.emit(KuudraEvent.of("pair", EventData.empty()));
        };
        CountDownLatch flowAEmitted = new CountDownLatch(1);
        AtomicInteger flowBOutputs = new AtomicInteger();
        try (KuudraRuntime runtime = new KuudraRuntime(16, 2)) {
            runtime.setComponentThreadSafe(shared, true);
            runtime.registerFlow(interpreterFlow("flow-a", shared, event -> flowAEmitted.countDown()));
            runtime.registerFlow(interpreterFlow("flow-b", shared, event -> flowBOutputs.incrementAndGet()));
            assertTrue(runtime.publish("flow-a", "interpreter", KuudraEvent.of("one", EventData.empty())));
            assertTrue(runtime.publish("flow-b", "interpreter", KuudraEvent.of("one", EventData.empty())));
            Thread.sleep(75);
            assertEquals(1, flowAEmitted.getCount());
            assertEquals(0, flowBOutputs.get());
            assertTrue(runtime.publish("flow-a", "interpreter", KuudraEvent.of("two", EventData.empty())));
            assertTrue(flowAEmitted.await(1, TimeUnit.SECONDS));
            assertEquals(0, flowBOutputs.get());
        }
    }

    @Test
    void pausingAbilityCancelsPendingInterpreterWindowAndClearsState() throws Exception {
        CountDownLatch emitted = new CountDownLatch(1);
        EventInterpreter interpreter = (event, context) -> {
            int count = (context.state().find("count").isPresent()
                    ? context.state().get("count", Integer.class) : 0) + 1;
            context.state().put("count", count);
            context.schedule("expire", Duration.ofMillis(80), () -> context.emit(
                    KuudraEvent.of("count-" + context.state().get("count", Integer.class), EventData.empty())));
        };
        AtomicReference<String> outputType = new AtomicReference<>();
        try (KuudraRuntime runtime = new KuudraRuntime(16, 2)) {
            runtime.registerFlow(new KuudraFlow("pausable", Map.of(
                    "interpreter", new FlowNode.InterpreterNode("interpreter", interpreter, Map.of()),
                    "sink", new FlowNode.AdapterNode("sink", (event, context) -> {
                        outputType.set(event.type()); emitted.countDown(); return List.of();
                    }, EventDomain.RAW)), Map.of("interpreter", List.of("sink"))));
            assertTrue(runtime.publish("pausable", "interpreter", KuudraEvent.of("one", EventData.empty())));
            Thread.sleep(20);
            runtime.setAbilityPaused("pausable", true);
            Thread.sleep(120);
            assertEquals(1, emitted.getCount());
            runtime.setAbilityPaused("pausable", false);
            assertTrue(runtime.publish("pausable", "interpreter", KuudraEvent.of("new", EventData.empty())));
            assertTrue(emitted.await(1, TimeUnit.SECONDS));
            assertEquals("count-1", outputType.get());
        }
    }

    @Test
    void interpreterContextIsRevokedWhenAbilityIsUnregistered() throws Exception {
        CountDownLatch captured = new CountDownLatch(1);
        AtomicReference<EventInterpreterContext> retained = new AtomicReference<>();
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            runtime.registerFlow(new KuudraFlow("temporary", Map.of(
                    "interpreter", new FlowNode.InterpreterNode("interpreter", (event, context) -> {
                        retained.set(context); captured.countDown();
                    }, Map.of())), Map.of()));
            assertTrue(runtime.publish("temporary", "interpreter", KuudraEvent.of("capture", EventData.empty())));
            assertTrue(captured.await(1, TimeUnit.SECONDS));
            runtime.unregisterAbility("temporary");
            assertFalse(retained.get().emit(KuudraEvent.of("late", EventData.empty())));
            assertThrows(KuudraException.class, () -> retained.get().state().snapshot());
        }
    }

    private KuudraFlow interpreterFlow(String id, EventInterpreter interpreter,
                                       java.util.function.Consumer<KuudraEvent> sink) {
        return new KuudraFlow(id, Map.of(
                "interpreter", new FlowNode.InterpreterNode("interpreter", interpreter, Map.of()),
                "sink", new FlowNode.AdapterNode("sink", (event, context) -> {
                    sink.accept(event); return List.of();
                }, EventDomain.RAW)), Map.of("interpreter", List.of("sink")));
    }

    @Test
    void handlerMayRequestCancellationOnlyForItsCurrentSession() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        AtomicReference<UUID> controlled = new AtomicReference<>();
        IngressConfiguration scheduling = new IngressConfiguration(
                SessionSchedulingPolicy.PARALLEL, SessionGroupScope.INGRESS, 1, 1);
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            runtime.registerFlow(new KuudraFlow("cancellable", Map.of(
                    "ingress", new FlowNode.IngressNode("ingress", (event, context) ->
                            IngressDecision.accept("group", event), scheduling, Map.of()),
                    "handler", new FlowNode.HandlerNode("handler", (event, context) -> {
                        controlled.set(context.sessionControl().sessionId());
                        assertEquals(context.sessionId(), context.sessionControl().sessionId());
                        assertTrue(context.sessionControl().requestCancellation("guard-rejected"));
                        requested.countDown();
                        return CompletableFuture.completedFuture(null);
                    }, Map.of())), Map.of("ingress", List.of("handler"))));

            assertTrue(runtime.publish("cancellable", "ingress", KuudraEvent.of("work", Map.of())));
            assertTrue(requested.await(1, TimeUnit.SECONDS));
            assertTrue(runtime.awaitNoActiveSessions(Duration.ofSeconds(1)));
            SessionSnapshot snapshot = runtime.sessions().snapshots().stream()
                    .filter(item -> item.id().equals(controlled.get())).findFirst().orElseThrow();
            assertEquals(SessionStatus.CANCELLED, snapshot.status());
        }
    }

    @Test
    void controlFlowContinuesWhileKernelPauseSuspendsDataFlow() throws Exception {
        CountDownLatch controlHandled = new CountDownLatch(1);
        CountDownLatch dataHandled = new CountDownLatch(1);
        try (KuudraRuntime runtime = new KuudraRuntime(8, 1)) {
            runtime.registerFlow(new KuudraFlow("data", 1, Map.of(
                    "sink", new FlowNode.AdapterNode("sink", (event, context) -> {
                        dataHandled.countDown();
                        return List.of();
                    }, EventDomain.RAW)), Map.of(), List.of(), FlowExecutionClass.DATA));
            runtime.registerFlow(new KuudraFlow("control", 1, Map.of(
                    "sink", new FlowNode.AdapterNode("sink", (event, context) -> {
                        assertEquals(ExecutionDecision.CONTINUE, context.executionControl().poll());
                        assertFalse(context.executionControl().suspensionReasons().contains(SuspensionReason.KERNEL));
                        controlHandled.countDown();
                        return List.of();
                    }, EventDomain.RAW)), Map.of(), List.of(), FlowExecutionClass.CONTROL));
            assertEquals(FlowExecutionClass.CONTROL,
                    runtime.flow("control").orElseThrow().executionClass());

            runtime.pause();
            assertFalse(runtime.publish("data", "sink", KuudraEvent.of("data", Map.of())));
            assertTrue(runtime.publish("control", "sink", KuudraEvent.of("control", Map.of())));
            assertTrue(controlHandled.await(1, TimeUnit.SECONDS));
            assertEquals(1, dataHandled.getCount());

            runtime.resume();
            assertTrue(runtime.publish("data", "sink", KuudraEvent.of("data", Map.of())));
            assertTrue(dataHandled.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void sharedNonThreadSafeComponentIsSerializedAcrossFlowBindings() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        EventAdapter adapter = (event, context) -> {
            int current = running.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            firstEntered.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            running.decrementAndGet();
            completed.countDown();
            return List.of();
        };
        try (KuudraRuntime runtime = new KuudraRuntime(16, 2)) {
            runtime.registerFlow(new KuudraFlow("flow-a", Map.of(
                    "adapter-a", new FlowNode.AdapterNode("adapter-a", adapter, EventDomain.RAW)), Map.of()));
            runtime.registerFlow(new KuudraFlow("flow-b", Map.of(
                    "adapter-b", new FlowNode.AdapterNode("adapter-b", adapter, EventDomain.RAW)), Map.of()));
            assertTrue(runtime.publish("flow-a", "adapter-a", KuudraEvent.of("one", Map.of())));
            assertTrue(runtime.publish("flow-b", "adapter-b", KuudraEvent.of("two", Map.of())));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals(1, peak.get());
            release.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(1, peak.get());
        }
    }

    @Test
    void sharedThreadSafeComponentMayRunConcurrentlyAcrossFlowBindings() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        EventAdapter adapter = (event, context) -> {
            int current = running.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            bothEntered.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            running.decrementAndGet();
            return List.of();
        };
        try (KuudraRuntime runtime = new KuudraRuntime(16, 2)) {
            runtime.setComponentThreadSafe(adapter, true);
            runtime.registerFlow(new KuudraFlow("flow-a", Map.of(
                    "adapter-a", new FlowNode.AdapterNode("adapter-a", adapter, EventDomain.RAW)), Map.of()));
            runtime.registerFlow(new KuudraFlow("flow-b", Map.of(
                    "adapter-b", new FlowNode.AdapterNode("adapter-b", adapter, EventDomain.RAW)), Map.of()));
            assertTrue(runtime.publish("flow-a", "adapter-a", KuudraEvent.of("one", Map.of())));
            assertTrue(runtime.publish("flow-b", "adapter-b", KuudraEvent.of("two", Map.of())));
            assertTrue(bothEntered.await(1, TimeUnit.SECONDS));
            assertEquals(2, peak.get());
            release.countDown();
        }
    }

    @Test
    void runtimeDoesNotOwnComponentLifecycle() {
        class ManagedAdapter implements EventAdapter, Lifecycle {
            private final AtomicInteger starts = new AtomicInteger();
            private final AtomicInteger stops = new AtomicInteger();
            @Override public List<KuudraEvent> adapt(KuudraEvent event, EventContext context) { return List.of(event); }
            @Override public CompletionStage<Void> start() { starts.incrementAndGet(); return CompletableFuture.completedFuture(null); }
            @Override public CompletionStage<Void> stop() { stops.incrementAndGet(); return CompletableFuture.completedFuture(null); }
        }
        ManagedAdapter adapter = new ManagedAdapter();
        KuudraRuntime runtime = new KuudraRuntime(8, 1);
        runtime.registerFlow(new KuudraFlow("flow", Map.of(
                "adapter", new FlowNode.AdapterNode("adapter", adapter, EventDomain.RAW)), Map.of()));
        runtime.close();
        assertEquals(0, adapter.starts.get());
        assertEquals(0, adapter.stops.get());
    }

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
                SessionSchedulingPolicy.PARALLEL, SessionGroupScope.INGRESS, 1, 1);
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
                SessionSchedulingPolicy.PARALLEL, SessionGroupScope.INGRESS, 1, 1);
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
                SessionSchedulingPolicy.PARALLEL, SessionGroupScope.INGRESS, 1, 1);
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
        IngressConfiguration scheduling = new IngressConfiguration(SessionSchedulingPolicy.PARALLEL, SessionGroupScope.INGRESS, 4, 4);
        try (KuudraRuntime runtime = new KuudraRuntime(32, 2)) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of(
                    "interpret", new FlowNode.InterpreterNode("interpret", (event, context) ->
                            context.emit(event.retype("interpreted")), Map.of()),
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
        FlowNode raw = new FlowNode.AdapterNode("raw", (event, context) -> List.of(event), EventDomain.RAW, Map.of("bad","${session.values.x}"));
        FlowNode handler = new FlowNode.HandlerNode("handler", (event, context) -> CompletableFuture.completedFuture(null), Map.of());
        assertThrows(KuudraException.class, () -> new KuudraFlow("bad",Map.of("raw",raw,"handler",handler),Map.of("raw",List.of("handler"))));
        try (KuudraRuntime runtime = new KuudraRuntime(8,1)) {
            assertThrows(KuudraException.class, () -> runtime.registerFlow(new KuudraFlow("bad-placeholder",Map.of("raw",raw),Map.of())));
        }
    }

    @Test
    void serialPolicyDefersSecondAdmissionUntilFirstLeaseCompletes() throws Exception {
        CountDownLatch firstStarted=new CountDownLatch(1);CountDownLatch release=new CountDownLatch(1);CountDownLatch both=new CountDownLatch(2);
        IngressConfiguration serial=new IngressConfiguration(SessionSchedulingPolicy.SERIAL,SessionGroupScope.INGRESS,1,4);
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
        IngressConfiguration scheduling = new IngressConfiguration(SessionSchedulingPolicy.PARALLEL, SessionGroupScope.INGRESS, 2, 2);
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
    void profileTransitionAtomicallyRecompilesGlobalArgumentsAndRejectsInvalidReplacement() throws Exception {
        BlockingQueue<String> values = new LinkedBlockingQueue<>();
        try (KuudraRuntime runtime = new KuudraRuntime(16, 1, Map.of("mode", "online"))) {
            runtime.registerFlow(new KuudraFlow("flow", Map.of(
                    "raw", new FlowNode.AdapterNode("raw", (event, context) -> {
                        values.add(context.configuration("mode", String.class));
                        return List.of();
                    }, EventDomain.RAW, Map.of("mode", "${global.mode}"))), Map.of()));
            assertTrue(runtime.publish("flow", "raw", KuudraEvent.of("input", Map.of())));
            assertEquals("online", values.poll(1, TimeUnit.SECONDS));

            runtime.beginProfileTransition(Duration.ofSeconds(1), Duration.ofSeconds(1));
            assertFalse(runtime.publish("flow", "raw", KuudraEvent.of("blocked", Map.of())));
            runtime.replaceGlobalContext(Map.of("mode", "quiet"));
            runtime.endProfileTransition();
            assertTrue(runtime.publish("flow", "raw", KuudraEvent.of("input", Map.of())));
            assertEquals("quiet", values.poll(1, TimeUnit.SECONDS));

            runtime.beginProfileTransition(Duration.ofSeconds(1), Duration.ofSeconds(1));
            assertThrows(IllegalArgumentException.class, () -> runtime.replaceGlobalContext(
                    Map.of("mode", "${global.missing}")));
            runtime.endProfileTransition();
            assertEquals("quiet", runtime.globalContext().get("mode", String.class));
            runtime.globalContext().put("mode", "${global.missing}");
            assertTrue(runtime.publish("flow", "raw", KuudraEvent.of("input", Map.of())));
            assertEquals("${global.missing}", values.poll(1, TimeUnit.SECONDS),
                    "Runtime writes are concrete values, not newly parsed templates");
        }
    }

    @Test
    void replacementPolicyQueuesBeforeSynchronousCancellationCallback() {
        SessionCoordinator coordinator = new SessionCoordinator();
        SessionCoordinator.Group group = new SessionCoordinator.Group("scope", "ingress", "key");
        IngressConfiguration configuration = new IngressConfiguration(SessionSchedulingPolicy.CANCEL_AND_REPLACE_PENDING,
                SessionGroupScope.INGRESS, 1, 1);
        AtomicReference<UUID> active = new AtomicReference<>();
        AtomicInteger launches = new AtomicInteger();
        Runnable launch = () -> { UUID id = UUID.randomUUID(); active.set(id); launches.incrementAndGet(); coordinator.activated(group,
                new SessionCoordinator.CoordinatedSession(id, "flow", "ingress/test/component", "key", Map.of()), List.of()); };
        assertTrue(coordinator.admit(group, configuration, launch, ignored -> { }));
        assertTrue(coordinator.admit(group, configuration, launch, id -> coordinator.terminal(group, id, ignored -> { })));
        assertEquals(2, launches.get());
        assertNotNull(active.get());
    }
}
