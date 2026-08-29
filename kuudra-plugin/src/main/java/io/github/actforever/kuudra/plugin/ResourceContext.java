package io.github.actforever.kuudra.plugin;

import io.github.actforever.kuudra.api.context.TypedValueMap;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Initialization context for one materialized plugin Resource. */
public record ResourceContext(String resourceReference, PluginContext plugin, Map<String, Object> options) {
    public ResourceContext {
        if (resourceReference == null || resourceReference.isBlank()) {
            throw new IllegalArgumentException("resourceReference must not be blank");
        }
        Objects.requireNonNull(plugin, "plugin");
        options = Map.copyOf(Objects.requireNonNull(options, "options"));
    }

    public Path home() { return plugin.pluginHome(); }
    public PluginLogger logger() { return plugin.logger(); }
    public TypedValueMap optionValues() { return TypedValueMap.of(options); }
    public <T> T option(String key, Class<T> type) { return TypedValueMap.get(options, key, type); }
    public <T> T option(String key, Class<T> type, T fallback) {
        return TypedValueMap.getOrDefault(options, key, type, fallback);
    }
}
