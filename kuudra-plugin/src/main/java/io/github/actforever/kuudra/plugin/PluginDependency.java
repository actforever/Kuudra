package io.github.actforever.kuudra.plugin;

/**
 * A dependency on one plugin identity and an accepted Forge/Maven-style version interval.
 * Valid examples include {@code [0.1.0,0.3.5)}, {@code (,1.0.0]}, {@code [1.0.0,)}, and the
 * exact range {@code [1.0.0]}. Version values use the format documented by {@link PluginMetadata}.
 */
public record PluginDependency(String namespace, String pluginId, boolean mandatory, String versionRange) {
    public PluginDependency {
        if (namespace == null || !namespace.matches("[a-z0-9][a-z0-9-]*"))
            throw new IllegalArgumentException("dependency namespace must match [a-z0-9][a-z0-9-]*");
        if (pluginId == null || !pluginId.matches("[a-z0-9][a-z0-9-]*"))
            throw new IllegalArgumentException("dependency pluginId must match [a-z0-9][a-z0-9-]*");
        PluginVersionRange.parse(versionRange);
    }

    public String identity() { return namespace + "/" + pluginId; }
    public boolean accepts(String version) { return PluginVersionRange.parse(versionRange).contains(version); }
}
