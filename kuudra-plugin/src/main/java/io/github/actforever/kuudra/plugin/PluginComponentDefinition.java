package io.github.actforever.kuudra.plugin;

import java.util.Objects;

/** A component type declared by a plugin annotation and addressable by configuration. */
public record PluginComponentDefinition(String pluginId, String namespace, PluginComponentKind kind, String name,
                                        Class<?> implementation, ComponentInstancePolicy instancePolicy,
                                        PluginComponentDocumentation documentation) {
    public PluginComponentDefinition {
        Objects.requireNonNull(pluginId, "pluginId"); Objects.requireNonNull(kind, "kind");
        if (namespace == null || !namespace.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("component namespace must match [a-z0-9][a-z0-9-]*");
        }
        if (name == null || name.isBlank()) throw new IllegalArgumentException("component name must not be blank");
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(instancePolicy, "instancePolicy");
        Objects.requireNonNull(documentation, "documentation");
    }
    public PluginComponentDefinition(String pluginId, String namespace, PluginComponentKind kind, String name,
                                     Class<?> implementation, ComponentInstancePolicy instancePolicy) {
        this(pluginId, namespace, kind, name, implementation, instancePolicy, PluginComponentDocumentation.EMPTY);
    }
    public PluginComponentDefinition(String pluginId, String namespace, PluginComponentKind kind, String name, Class<?> implementation) {
        this(pluginId, namespace, kind, name, implementation, ComponentInstancePolicy.DEFAULT, PluginComponentDocumentation.EMPTY);
    }
    public String reference() { return kind.prefix() + "/" + namespace + "/" + name; }
}
