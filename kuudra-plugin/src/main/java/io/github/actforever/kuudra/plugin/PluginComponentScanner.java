package io.github.actforever.kuudra.plugin;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** Discovers annotation-declared components from a plugin archive. */
final class PluginComponentScanner {
    List<PluginComponentDefinition> scan(Path archive, URLClassLoader loader, String pluginId, String namespace) throws IOException {
        List<PluginComponentDefinition> definitions = new ArrayList<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.equals("module-info.class")) continue;
                Class<?> type;
                try { type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, loader); }
                catch (ClassNotFoundException | LinkageError error) { throw new IOException("Cannot inspect plugin class " + name, error); }
                definition(pluginId, namespace, type).ifPresent(definitions::add);
            }
        }
        return List.copyOf(definitions);
    }

    private java.util.Optional<PluginComponentDefinition> definition(String pluginId, String namespace, Class<?> type) {
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventSource.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventSource.class); return component(pluginId, namespace, PluginComponentKind.EVENT_SOURCE, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventInterpreter.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventInterpreter.class); return component(pluginId, namespace, PluginComponentKind.EVENT_INTERPRETER, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventAdapter.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventAdapter.class); return component(pluginId, namespace, PluginComponentKind.EVENT_ADAPTER, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Ingress.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Ingress.class); return component(pluginId, namespace, PluginComponentKind.INGRESS, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventHandler.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventHandler.class); return component(pluginId, namespace, PluginComponentKind.EVENT_HANDLER, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Egress.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Egress.class); return component(pluginId, namespace, PluginComponentKind.EGRESS, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Action.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Action.class); return component(pluginId, namespace, PluginComponentKind.ACTION, annotation.value(), type, annotation.instancePolicy()); }
        return java.util.Optional.empty();
    }

    private java.util.Optional<PluginComponentDefinition> component(String pluginId, String namespace, PluginComponentKind kind, String name, Class<?> type,
                                                                     io.github.actforever.kuudra.plugin.annotation.InstancePolicy policy) {
        Class<?> expected = switch (kind) {
            case EVENT_SOURCE -> io.github.actforever.kuudra.api.EventSource.class;
            case EVENT_INTERPRETER -> io.github.actforever.kuudra.api.EventInterpreter.class;
            case EVENT_ADAPTER -> io.github.actforever.kuudra.api.EventAdapter.class;
            case INGRESS -> io.github.actforever.kuudra.api.Ingress.class;
            case EVENT_HANDLER -> io.github.actforever.kuudra.api.EventHandler.class;
            case EGRESS -> io.github.actforever.kuudra.api.Egress.class;
            case ACTION -> io.github.actforever.kuudra.api.Action.class;
        };
        if (!expected.isAssignableFrom(type)) throw new IllegalArgumentException(type.getName() + " annotated as " + kind + " but does not implement " + expected.getName());
        ComponentInstancePolicy instancePolicy = new ComponentInstancePolicy(policy.maxInstances(), policy.limitScope(),
                policy.exclusivityDomain().isBlank() ? namespace + "/" + name : policy.exclusivityDomain(), policy.shareable(), policy.threadSafe());
        return java.util.Optional.of(new PluginComponentDefinition(pluginId, namespace, kind, name, type, instancePolicy));
    }
}
