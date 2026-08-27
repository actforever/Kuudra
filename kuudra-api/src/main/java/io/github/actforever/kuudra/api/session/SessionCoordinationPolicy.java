package io.github.actforever.kuudra.api.session;

import io.github.actforever.kuudra.api.component.IngressConfiguration;

import java.util.List;
import java.util.Map;

/**
 * Immutable policy selected automatically from labels produced by an Ingress.
 * Selection and dependency resolution are always constrained to one Flow.
 */
public record SessionCoordinationPolicy(String name, Map<String, String> matchLabels,
                                        IngressConfiguration scheduling,
                                        List<SessionDependencyRequirement> dependencies) {
    public SessionCoordinationPolicy {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        matchLabels = Map.copyOf(matchLabels);
        if (matchLabels.isEmpty()) throw new IllegalArgumentException("matchLabels must not be empty");
        if (scheduling == null) scheduling = IngressConfiguration.DEFAULT;
        dependencies = List.copyOf(dependencies);
    }

    public boolean matches(Map<String, String> labels) {
        return matchLabels.entrySet().stream().allMatch(entry -> entry.getValue().equals(labels.get(entry.getKey())));
    }
}
