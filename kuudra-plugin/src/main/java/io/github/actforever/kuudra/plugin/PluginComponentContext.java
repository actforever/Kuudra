package io.github.actforever.kuudra.plugin;

import java.util.Objects;

/** Context for one configured plugin component; delegates shared services to its owning plugin. */
public record PluginComponentContext(String componentReference, PluginContext plugin) {
    public PluginComponentContext {
        if (componentReference == null || componentReference.isBlank()) throw new IllegalArgumentException("componentReference must not be blank");
        Objects.requireNonNull(plugin, "plugin");
    }
}
