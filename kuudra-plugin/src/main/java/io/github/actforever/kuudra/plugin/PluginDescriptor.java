package io.github.actforever.kuudra.plugin;

import java.util.List;
import java.util.Objects;

/** Immutable metadata required by the minimal plugin lifecycle manager. */
public record PluginDescriptor(String id, List<String> requires) {
    public PluginDescriptor {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Plugin id must not be blank");
        }
        requires = List.copyOf(Objects.requireNonNull(requires, "requires"));
        if (requires.stream().anyMatch(required -> required == null || required.isBlank())) {
            throw new IllegalArgumentException("Plugin dependencies must not be blank");
        }
    }
}
