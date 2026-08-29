package io.github.actforever.kuudra.plugin;

import java.util.*;

/** Registry of plugin ResourceTemplates keyed by canonical template reference. */
public final class ResourceTemplateRegistry {
    private final Map<String, ResourceTemplateDefinition> definitions = new LinkedHashMap<>();

    public synchronized void register(ResourceTemplateDefinition definition) {
        if (definitions.putIfAbsent(definition.reference(), definition) != null) {
            throw new IllegalArgumentException("Duplicate ResourceTemplate: " + definition.reference());
        }
    }
    public synchronized Optional<ResourceTemplateDefinition> find(String reference) {
        return Optional.ofNullable(definitions.get(reference));
    }
    public synchronized Map<String, ResourceTemplateDefinition> definitions() {
        return Map.copyOf(definitions);
    }
    public synchronized Object create(String reference) {
        ResourceTemplateDefinition definition = find(reference)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ResourceTemplate: " + reference));
        try { return definition.implementation().getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot instantiate ResourceTemplate " + reference, error);
        }
    }
}
