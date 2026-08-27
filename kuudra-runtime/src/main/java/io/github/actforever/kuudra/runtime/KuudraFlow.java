package io.github.actforever.kuudra.runtime;

import java.util.*;
import java.util.stream.Collectors;

/** Immutable, domain-checked event graph. Source bindings target RAW nodes. */
public record KuudraFlow(String id, long revision, Map<String, FlowNode> nodes, Map<String, List<String>> edges,
                         List<io.github.actforever.kuudra.api.session.SessionCoordinationPolicy> coordinationPolicies,
                         io.github.actforever.kuudra.api.runtime.FlowExecutionClass executionClass) {
    public KuudraFlow(String id, Map<String, FlowNode> nodes, Map<String, List<String>> edges) {
        this(id, 1, nodes, edges, List.of(), io.github.actforever.kuudra.api.runtime.FlowExecutionClass.DATA);
    }
    public KuudraFlow(String id, long revision, Map<String, FlowNode> nodes, Map<String, List<String>> edges) {
        this(id, revision, nodes, edges, List.of(), io.github.actforever.kuudra.api.runtime.FlowExecutionClass.DATA);
    }
    public KuudraFlow(String id, long revision, Map<String, FlowNode> nodes, Map<String, List<String>> edges,
                      List<io.github.actforever.kuudra.api.session.SessionCoordinationPolicy> coordinationPolicies) {
        this(id, revision, nodes, edges, coordinationPolicies, io.github.actforever.kuudra.api.runtime.FlowExecutionClass.DATA);
    }
    public KuudraFlow {
        if (id == null || id.isBlank()) throw new io.github.actforever.kuudra.api.KuudraException("Flow id must not be blank");
        if (revision < 1) throw new io.github.actforever.kuudra.api.KuudraException("Flow revision must be positive");
        nodes = Map.copyOf(Objects.requireNonNull(nodes));
        if (nodes.isEmpty()) throw new io.github.actforever.kuudra.api.KuudraException("Flow must contain nodes");
        edges = edges.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
        coordinationPolicies = List.copyOf(coordinationPolicies);
        Objects.requireNonNull(executionClass, "executionClass");
        for (Map.Entry<String, List<String>> edge : edges.entrySet()) {
            FlowNode source = require(nodes, edge.getKey(), "source");
            for (String targetId : edge.getValue()) {
                FlowNode target = require(nodes, targetId, "target");
                if (source.outputDomain() != target.inputDomain())
                    throw new io.github.actforever.kuudra.api.KuudraException("Flow domain mismatch: " + source.id() + "(" + source.outputDomain() + ") -> " + target.id() + "(" + target.inputDomain() + ")");
            }
        }
    }
    public List<String> next(String nodeId) { return edges.getOrDefault(nodeId, List.of()); }
    public FlowNode node(String nodeId) { return require(nodes, nodeId, "node"); }
    private static FlowNode require(Map<String, FlowNode> nodes, String id, String role) {
        FlowNode node = nodes.get(id); if (node == null) throw new io.github.actforever.kuudra.api.KuudraException("Unknown " + role + " node: " + id); return node;
    }
}
