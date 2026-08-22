package io.github.actforever.kuudra.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable Event graph; EventSource bindings choose their initial target node. */
public record KuudraFlow(String id, Map<String, FlowNode> nodes, Map<String, List<String>> edges) {
    public KuudraFlow {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        nodes = Map.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (nodes.isEmpty()) throw new IllegalArgumentException("flow must contain nodes");
        edges = edges.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        for (Map.Entry<String, List<String>> edge : edges.entrySet()) {
            if (!nodes.containsKey(edge.getKey())) throw new IllegalArgumentException("unknown source node: " + edge.getKey());
            for (String target : edge.getValue()) if (!nodes.containsKey(target)) throw new IllegalArgumentException("unknown target node: " + target);
        }
    }
    public List<String> next(String nodeId) { return edges.getOrDefault(nodeId, List.of()); }
    public FlowNode node(String nodeId) {
        FlowNode node = nodes.get(nodeId); if (node == null) throw new IllegalArgumentException("unknown flow node: " + nodeId); return node;
    }
}
