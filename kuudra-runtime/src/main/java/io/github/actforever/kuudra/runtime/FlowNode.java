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
import java.util.Map;
import java.util.Objects;

/** Compiled Flow node. Ingress and Egress are the only domain-changing nodes. */
public sealed interface FlowNode permits FlowNode.SourceNode, FlowNode.AdapterNode, FlowNode.InterpreterNode,
        FlowNode.IngressNode, FlowNode.JoinIngressNode, FlowNode.HandlerNode, FlowNode.ControllerNode, FlowNode.EgressNode {
    String id();
    Map<String, Object> configuration();

    record SourceNode(String id, EventSource source) implements FlowNode {
        public SourceNode { requireId(id); Objects.requireNonNull(source); }
        @Override public Map<String, Object> configuration() { return Map.of(); }
    }

    record AdapterNode(String id, EventAdapter adapter, EventDomain domain, Map<String, Object> configuration) implements FlowNode {
        public AdapterNode { requireId(id); Objects.requireNonNull(adapter); Objects.requireNonNull(domain); configuration = Map.copyOf(configuration); }
        public AdapterNode(String id, EventAdapter adapter, EventDomain domain) { this(id, adapter, domain, Map.of()); }
    }
    record InterpreterNode(String id, EventInterpreter interpreter, Map<String, Object> configuration) implements FlowNode {
        public InterpreterNode { requireId(id); Objects.requireNonNull(interpreter); configuration = Map.copyOf(configuration); }
    }
    record IngressNode(String id, String instanceId, Ingress ingress, IngressConfiguration defaultScheduling,
                       java.util.List<SessionDependencyRequirement> dependencies,
                       Map<String, Object> configuration) implements FlowNode {
        public IngressNode { requireId(id); requireId(instanceId); Objects.requireNonNull(ingress); Objects.requireNonNull(defaultScheduling); dependencies=java.util.List.copyOf(dependencies); configuration = Map.copyOf(configuration); }
        public IngressNode(String id, String instanceId, Ingress ingress, IngressConfiguration defaultScheduling,
                           Map<String, Object> configuration) {
            this(id,instanceId,ingress,defaultScheduling,java.util.List.of(),configuration);
        }
        public IngressNode(String id, Ingress ingress, IngressConfiguration defaultScheduling, Map<String, Object> configuration) {
            this(id, id, ingress, defaultScheduling, java.util.List.of(), configuration);
        }
    }
    record HandlerNode(String id, EventHandler handler, Map<String, Object> configuration) implements FlowNode {
        public HandlerNode { requireId(id); Objects.requireNonNull(handler); configuration = Map.copyOf(configuration); }
    }
    record JoinIngressNode(String id, String instanceId, Ingress ingress, String targetIngress,
                           Map<String, Object> configuration) implements FlowNode {
        public JoinIngressNode {
            requireId(id); requireId(instanceId); Objects.requireNonNull(ingress);
            requireId(targetIngress); configuration = Map.copyOf(configuration);
        }
    }
    record ControllerNode(String id, Object controller, String handlerName,
                          java.util.function.BiFunction<KuudraEvent, EventHandlerContext,
                                  java.util.concurrent.CompletionStage<Void>> handler,
                          Map<String, Object> configuration) implements FlowNode {
        public ControllerNode {
            requireId(id); Objects.requireNonNull(controller); requireId(handlerName);
            Objects.requireNonNull(handler); configuration = Map.copyOf(configuration);
        }
    }
    record EgressNode(String id, Egress egress, Map<String, Object> configuration) implements FlowNode {
        public EgressNode { requireId(id); Objects.requireNonNull(egress); configuration = Map.copyOf(configuration); }
    }

    default EventDomain inputDomain() {
        if (this instanceof SourceNode || this instanceof InterpreterNode || this instanceof IngressNode || this instanceof JoinIngressNode) return EventDomain.RAW;
        if (this instanceof HandlerNode || this instanceof ControllerNode || this instanceof EgressNode) return EventDomain.SESSION;
        return ((AdapterNode) this).domain();
    }
    default EventDomain outputDomain() {
        if (this instanceof IngressNode || this instanceof JoinIngressNode) return EventDomain.SESSION;
        if (this instanceof SourceNode) return EventDomain.RAW;
        if (this instanceof EgressNode) return EventDomain.RAW;
        return inputDomain();
    }
    private static void requireId(String id) { if (id == null || id.isBlank()) throw new KuudraException("Flow node id must not be blank"); }
}
