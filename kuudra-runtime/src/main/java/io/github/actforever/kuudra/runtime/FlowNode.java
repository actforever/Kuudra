package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.*;
import java.util.Map;
import java.util.Objects;

/** Compiled Flow node. Ingress and Egress are the only domain-changing nodes. */
public sealed interface FlowNode permits FlowNode.AdapterNode, FlowNode.InterpreterNode,
        FlowNode.IngressNode, FlowNode.HandlerNode, FlowNode.EgressNode {
    String id();
    Map<String, Object> configuration();

    record AdapterNode(String id, EventAdapter adapter, EventDomain domain, Map<String, Object> configuration) implements FlowNode {
        public AdapterNode { requireId(id); Objects.requireNonNull(adapter); Objects.requireNonNull(domain); configuration = Map.copyOf(configuration); }
        public AdapterNode(String id, EventAdapter adapter, EventDomain domain) { this(id, adapter, domain, Map.of()); }
    }
    record InterpreterNode(String id, RawEventInterpreter interpreter, Map<String, Object> configuration) implements FlowNode {
        public InterpreterNode { requireId(id); Objects.requireNonNull(interpreter); configuration = Map.copyOf(configuration); }
    }
    record IngressNode(String id, Ingress ingress, IngressConfiguration scheduling, Map<String, Object> configuration) implements FlowNode {
        public IngressNode { requireId(id); Objects.requireNonNull(ingress); Objects.requireNonNull(scheduling); configuration = Map.copyOf(configuration); }
    }
    record HandlerNode(String id, EventHandler handler, Map<String, Object> configuration) implements FlowNode {
        public HandlerNode { requireId(id); Objects.requireNonNull(handler); configuration = Map.copyOf(configuration); }
    }
    record EgressNode(String id, Egress egress, Map<String, Object> configuration) implements FlowNode {
        public EgressNode { requireId(id); Objects.requireNonNull(egress); configuration = Map.copyOf(configuration); }
    }

    default EventDomain inputDomain() {
        if (this instanceof InterpreterNode || this instanceof IngressNode) return EventDomain.RAW;
        if (this instanceof HandlerNode || this instanceof EgressNode) return EventDomain.SESSION;
        return ((AdapterNode) this).domain();
    }
    default EventDomain outputDomain() {
        if (this instanceof IngressNode) return EventDomain.SESSION;
        if (this instanceof EgressNode) return EventDomain.RAW;
        return inputDomain();
    }
    private static void requireId(String id) { if (id == null || id.isBlank()) throw new KuudraException("Flow node id must not be blank"); }
}
