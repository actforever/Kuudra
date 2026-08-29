package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.KuudraException;
import io.github.actforever.kuudra.api.runtime.AbilityExecutionClass;

import java.util.*;
import java.util.stream.Collectors;

/** Immutable, domain-checked Ability graph. */
public record KuudraAbility(String id, long revision, Map<String, FlowNode> nodes,
                            Map<String, List<String>> edges, AbilityExecutionClass executionClass) {
    public KuudraAbility(String id, Map<String, FlowNode> nodes, Map<String, List<String>> edges) {
        this(id, 1, nodes, edges, AbilityExecutionClass.DATA);
    }

    public KuudraAbility {
        if (id == null || id.isBlank()) throw new KuudraException("Ability id must not be blank");
        if (revision < 1) throw new KuudraException("Ability revision must be positive");
        nodes = Map.copyOf(Objects.requireNonNull(nodes));
        if (nodes.isEmpty()) throw new KuudraException("Ability must contain nodes");
        edges = edges.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        Objects.requireNonNull(executionClass, "executionClass");
        for (Map.Entry<String, List<String>> edge : edges.entrySet()) {
            FlowNode source = require(nodes, edge.getKey(), "source");
            for (String targetId : edge.getValue()) {
                FlowNode target = require(nodes, targetId, "target");
                if (source.outputDomain() != target.inputDomain()) {
                    throw new KuudraException("Ability domain mismatch: " + source.id() + "(" + source.outputDomain()
                            + ") -> " + target.id() + "(" + target.inputDomain() + ")");
                }
            }
        }
    }

    KuudraFlow asRuntimeGraph() {
        return new KuudraFlow(id, revision, nodes, edges, List.of(),
                executionClass == AbilityExecutionClass.CONTROL
                        ? io.github.actforever.kuudra.api.runtime.FlowExecutionClass.CONTROL
                        : io.github.actforever.kuudra.api.runtime.FlowExecutionClass.DATA);
    }

    private static FlowNode require(Map<String, FlowNode> nodes, String id, String role) {
        FlowNode node = nodes.get(id);
        if (node == null) throw new KuudraException("Unknown " + role + " node: " + id);
        return node;
    }
}
