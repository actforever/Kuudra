package io.github.actforever.kuudra.plugin;

import java.nio.file.Path;
import java.util.Objects;

/** Resources allocated for one activated plugin. */
public record PluginContext(String pluginId, Path home, PluginResourceRegistry resources, PluginRuntimeServices runtime) {
    public PluginContext {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(runtime, "runtime");
    }
}
