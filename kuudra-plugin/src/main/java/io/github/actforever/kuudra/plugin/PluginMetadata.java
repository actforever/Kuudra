package io.github.actforever.kuudra.plugin;

import java.util.List;
import java.util.Objects;

/**
 * Metadata read from META-INF/kuudra-plugin/metadata.toml.
 *
 * <p>A plugin version consists of dot-separated non-negative numeric segments and may carry a
 * {@code -prerelease} and/or {@code +build} suffix, for example {@code 1.4.0},
 * {@code 1.4.0-alpha.2}, or {@code 1.4.0+20260824}. A leading {@code v} is not accepted.</p>
 */
public record PluginMetadata(String id, String namespace, String version, String entrypoint, List<PluginDependency> dependencies) {
    public PluginMetadata {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("plugin id must not be blank");
        if (namespace == null || !namespace.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("plugin namespace must match [a-z0-9][a-z0-9-]*");
        }
        PluginVersionRange.parse("[" + version + "]");
        if (entrypoint == null || entrypoint.isBlank()) throw new IllegalArgumentException("plugin entrypoint must not be blank");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        if (dependencies.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("plugin dependency must not be null");
        if (dependencies.stream().map(PluginDependency::identity).distinct().count() != dependencies.size())
            throw new IllegalArgumentException("plugin dependencies must not contain duplicate identities");
    }
}
