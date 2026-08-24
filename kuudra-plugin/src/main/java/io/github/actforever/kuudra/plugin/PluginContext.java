package io.github.actforever.kuudra.plugin;

import java.nio.file.Path;
import java.util.Objects;

/** Resources allocated for one activated plugin. */
public record PluginContext(String pluginId, String namespace, Path home, PluginResourceRegistry resources,
                            PluginRuntimeServices runtime, PluginLogger logger) {
    public PluginContext {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(logger, "logger");
    }

    public PluginContext(String pluginId, Path home, PluginResourceRegistry resources, PluginRuntimeServices runtime) {
        this(pluginId, pluginId, home, resources, runtime, (level, message, fields, error) -> { });
    }

    /** Explicitly named home-directory API for plugin persistence. */
    public Path pluginHome() { return home; }
}
