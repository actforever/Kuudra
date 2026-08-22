package io.github.actforever.kuudra.plugin;

import java.util.List;
import java.util.Objects;

/** Metadata read from META-INF/kuudra-plugin/metadata.toml. */
public record PluginMetadata(String id, String namespace, String version, String entrypoint, List<String> dependencies) {
    public PluginMetadata {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("plugin id must not be blank");
        if (namespace == null || !namespace.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("plugin namespace must match [a-z0-9][a-z0-9-]*");
        }
        if (version == null || version.isBlank()) throw new IllegalArgumentException("plugin version must not be blank");
        if (entrypoint == null || entrypoint.isBlank()) throw new IllegalArgumentException("plugin entrypoint must not be blank");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        if (dependencies.stream().anyMatch(dependency -> dependency == null || dependency.isBlank())) {
            throw new IllegalArgumentException("plugin dependency must not be blank");
        }
    }
}
