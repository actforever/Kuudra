package io.github.actforever.kuudra.plugin;

import java.util.List;
import java.util.Objects;

/** Plugin-provided constructor and endpoint contract for one Resource kind. */
public record ResourceTemplateDefinition(String pluginId, String namespace, ResourceTemplateKind kind,
                                         String name, Class<?> implementation, ResourcePolicy policy,
                                         ResourceTemplateDocumentation documentation,
                                         List<ControllerHandlerDefinition> handlers) {
    public ResourceTemplateDefinition {
        Objects.requireNonNull(pluginId, "pluginId");
        if (namespace == null || !namespace.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("template namespace must match [a-z0-9][a-z0-9-]*");
        }
        Objects.requireNonNull(kind, "kind");
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("template name must match [a-z0-9][a-z0-9-]*");
        }
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(documentation, "documentation");
        handlers = List.copyOf(handlers);
        if (kind == ResourceTemplateKind.CONTROLLER && handlers.isEmpty()) {
            throw new IllegalArgumentException("Controller template must expose at least one handler");
        }
        if (kind != ResourceTemplateKind.CONTROLLER && !handlers.isEmpty()) {
            throw new IllegalArgumentException("Only Controller templates expose handlers");
        }
    }

    public String reference() { return kind.prefix() + "/" + namespace + "/" + pluginId + "/" + name; }
    public ControllerHandlerDefinition handler(String handlerName) {
        return handlers.stream().filter(item -> item.name().equals(handlerName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Controller handler: " + handlerName));
    }
}
