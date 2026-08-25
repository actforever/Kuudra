package io.github.actforever.kuudra.plugin;

import java.util.Map;
import java.util.Objects;

/** Context for one configured plugin component; delegates shared services to its owning plugin. */
public record PluginComponentContext(String componentReference, PluginContext plugin, Map<String, Object> configuration) {
    public PluginComponentContext {
        if (componentReference == null || componentReference.isBlank()) throw new IllegalArgumentException("componentReference must not be blank");
        Objects.requireNonNull(plugin, "plugin");
        configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
    }

    public PluginComponentContext(String componentReference, PluginContext plugin) {
        this(componentReference, plugin, Map.of());
    }

    public java.nio.file.Path pluginHome() { return plugin.pluginHome(); }
    public PluginLogger logger() { return plugin.logger(); }

    public io.github.actforever.kuudra.api.context.TypedValueMap configurationValues() {
        return io.github.actforever.kuudra.api.context.TypedValueMap.of(configuration);
    }

    public <T> T configuration(String key, Class<T> type) {
        return io.github.actforever.kuudra.api.context.TypedValueMap.get(configuration, key, type);
    }

    public <T> T configuration(String key, Class<T> type, T fallback) {
        return io.github.actforever.kuudra.api.context.TypedValueMap.getOrDefault(configuration, key, type, fallback);
    }
}
