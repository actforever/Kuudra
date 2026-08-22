package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.Actor;
import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.EventAdapter;
import io.github.actforever.kuudra.api.EventContext;
import io.github.actforever.kuudra.api.EventProcessor;
import io.github.actforever.kuudra.api.SessionSpec;

import java.util.List;
import java.util.Objects;
import io.github.actforever.kuudra.api.EventEmitter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A node in an Event graph. SessionAllocator is a core node, never a plugin SPI. */
public sealed interface FlowNode permits FlowNode.AdapterNode, FlowNode.ProcessorNode, FlowNode.AllocatorNode, FlowNode.ActorNode {
    String id();

    record AdapterNode(String id, EventAdapter adapter, java.util.Map<String, Object> configuration) implements FlowNode {
        public AdapterNode { requireId(id); Objects.requireNonNull(adapter, "adapter"); configuration = java.util.Map.copyOf(configuration); }
        public AdapterNode(String id, EventAdapter adapter) { this(id, adapter, java.util.Map.of()); }
        CompletionStage<List<Event>> apply(Event event, EventContext context) { return CompletableFuture.completedFuture(adapter.adapt(event, context)); }
    }
    record ProcessorNode(String id, EventProcessor processor, java.util.Map<String, Object> configuration) implements FlowNode {
        public ProcessorNode { requireId(id); Objects.requireNonNull(processor, "processor"); configuration = java.util.Map.copyOf(configuration); }
        public ProcessorNode(String id, EventProcessor processor) { this(id, processor, java.util.Map.of()); }
        CompletionStage<List<Event>> apply(Event event, EventContext context) { return CompletableFuture.completedFuture(processor.process(event, context)); }
    }
    record AllocatorNode(String id, SessionSpec sessionSpec) implements FlowNode {
        public AllocatorNode { requireId(id); Objects.requireNonNull(sessionSpec, "sessionSpec"); }
    }
    record ActorNode(String id, Actor actor, java.util.Map<String, Object> configuration) implements FlowNode {
        public ActorNode { requireId(id); Objects.requireNonNull(actor, "actor"); configuration = java.util.Map.copyOf(configuration); }
        public ActorNode(String id, Actor actor) { this(id, actor, java.util.Map.of()); }
        CompletionStage<Void> apply(Event event, EventContext context, EventEmitter emitter) {
            if (!event.hasSession()) return CompletableFuture.failedFuture(new IllegalArgumentException("Actor requires a session-bound Event"));
            return actor.act(event, new io.github.actforever.kuudra.api.ActionContext(event.session().id(), event.session().flowId(),
                    context.sessionValues(), context.sessionContext(), context.flowValues(), context.flowContext(),
                    context.cancellationToken(), emitter, context.globalValues(), context.globalContext(), context.configuration()));
        }
    }
    private static void requireId(String id) { if (id == null || id.isBlank()) throw new IllegalArgumentException("node id must not be blank"); }
}
