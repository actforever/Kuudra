package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.Actor;
import io.github.actforever.kuudra.api.Event;
import io.github.actforever.kuudra.api.EventAdapter;
import io.github.actforever.kuudra.api.EventContext;
import io.github.actforever.kuudra.api.EventProcessor;
import io.github.actforever.kuudra.api.SessionSpec;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A node in an Event graph. SessionAllocator is a core node, never a plugin SPI. */
public sealed interface FlowNode permits FlowNode.AdapterNode, FlowNode.ProcessorNode, FlowNode.AllocatorNode, FlowNode.ActorNode {
    String id();

    record AdapterNode(String id, EventAdapter adapter) implements FlowNode {
        public AdapterNode { requireId(id); Objects.requireNonNull(adapter, "adapter"); }
        CompletionStage<List<Event>> apply(Event event, EventContext context) { return CompletableFuture.completedFuture(adapter.adapt(event, context)); }
    }
    record ProcessorNode(String id, EventProcessor processor) implements FlowNode {
        public ProcessorNode { requireId(id); Objects.requireNonNull(processor, "processor"); }
        CompletionStage<List<Event>> apply(Event event, EventContext context) { return CompletableFuture.completedFuture(processor.process(event, context)); }
    }
    record AllocatorNode(String id, SessionSpec sessionSpec) implements FlowNode {
        public AllocatorNode { requireId(id); Objects.requireNonNull(sessionSpec, "sessionSpec"); }
    }
    record ActorNode(String id, Actor actor) implements FlowNode {
        public ActorNode { requireId(id); Objects.requireNonNull(actor, "actor"); }
        CompletionStage<List<Event>> apply(Event event, EventContext context) {
            if (!event.hasSession()) return CompletableFuture.failedFuture(new IllegalArgumentException("Actor requires a session-bound Event"));
            return actor.act(event, new io.github.actforever.kuudra.api.ActionContext(event.session().id(), event.session().flowId(), context.sessionValues(), context.sessionContext(), context.cancellationToken()));
        }
    }
    private static void requireId(String id) { if (id == null || id.isBlank()) throw new IllegalArgumentException("node id must not be blank"); }
}
