package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.ActionContext;
import io.github.actforever.kuudra.api.Actor;
import io.github.actforever.kuudra.api.Signal;
import io.github.actforever.kuudra.api.SignalAdapter;
import io.github.actforever.kuudra.api.SignalContext;
import io.github.actforever.kuudra.api.SignalProcessor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A session-stage node. Each node receives one signal and returns zero or more successor signals. */
public sealed interface FlowNode permits FlowNode.AdapterNode, FlowNode.ProcessorNode, FlowNode.ActorNode {
    String id();
    CompletionStage<List<Signal>> apply(Signal signal, SignalContext context);

    record AdapterNode(String id, SignalAdapter adapter) implements FlowNode {
        @Override public CompletionStage<List<Signal>> apply(Signal signal, SignalContext context) {
            return CompletableFuture.completedFuture(adapter.adapt(signal, context));
        }
    }
    record ProcessorNode(String id, SignalProcessor processor) implements FlowNode {
        @Override public CompletionStage<List<Signal>> apply(Signal signal, SignalContext context) {
            return CompletableFuture.completedFuture(processor.process(signal, context));
        }
    }
    record ActorNode(String id, Actor actor) implements FlowNode {
        @Override public CompletionStage<List<Signal>> apply(Signal signal, SignalContext context) {
            return actor.act(signal, new ActionContext(context.sessionId(), context.flowId(), context.sessionValues(), context.cancellationToken()));
        }
    }
}
