package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.SessionProcessor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable session graph. The SessionProcessor is the only RawSignal entry. */
public record KuudraFlow(String id, SessionProcessor sessionProcessor, String entryNodeId,
                         Map<String, FlowNode> nodes, Map<String, List<String>> edges) {
    public KuudraFlow {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        Objects.requireNonNull(sessionProcessor, "sessionProcessor");
        if (entryNodeId == null || entryNodeId.isBlank()) throw new IllegalArgumentException("entryNodeId must not be blank");
        nodes = Map.copyOf(nodes);
        if (!nodes.containsKey(entryNodeId)) throw new IllegalArgumentException("entry node does not exist: " + entryNodeId);
        edges = edges.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        for (Map.Entry<String, List<String>> edge : edges.entrySet()) {
            if (!nodes.containsKey(edge.getKey())) throw new IllegalArgumentException("unknown source node: " + edge.getKey());
            for (String target : edge.getValue()) if (!nodes.containsKey(target)) throw new IllegalArgumentException("unknown target node: " + target);
        }
    }
    public List<String> next(String nodeId) { return edges.getOrDefault(nodeId, List.of()); }
}
