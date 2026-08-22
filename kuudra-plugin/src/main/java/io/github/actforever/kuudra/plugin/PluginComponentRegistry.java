package io.github.actforever.kuudra.plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Configuration-facing registry for plugin component definitions. */
public final class PluginComponentRegistry {
    private final Map<String, PluginComponentDefinition> definitions = new LinkedHashMap<>();

    public synchronized void register(PluginComponentDefinition definition) {
        if (definitions.putIfAbsent(definition.reference(), definition) != null) {
            throw new IllegalArgumentException("Duplicate plugin component: " + definition.reference());
        }
    }
    public synchronized Optional<PluginComponentDefinition> find(String reference) {
        return Optional.ofNullable(definitions.get(reference));
    }
    public synchronized Map<String, PluginComponentDefinition> definitions() { return Map.copyOf(definitions); }

    public <T> T create(String reference, Class<T> expectedType) {
        PluginComponentDefinition definition = find(reference).orElseThrow(() -> new IllegalArgumentException("Unknown component: " + reference));
        if (!expectedType.isAssignableFrom(definition.implementation())) {
            throw new IllegalArgumentException(reference + " does not implement " + expectedType.getName());
        }
        try {
            return expectedType.cast(definition.implementation().getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Component requires a public no-argument constructor: " + reference, error);
        }
    }
}
