package io.github.actforever.kuudra.plugin;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** Discovers annotation-declared components from a plugin archive. */
final class PluginComponentScanner {
    List<PluginComponentDefinition> scan(Path archive, URLClassLoader loader, String pluginId) throws IOException {
        List<PluginComponentDefinition> definitions = new ArrayList<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.equals("module-info.class")) continue;
                Class<?> type;
                try { type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, loader); }
                catch (ClassNotFoundException | LinkageError error) { throw new IOException("Cannot inspect plugin class " + name, error); }
                definition(pluginId, type).ifPresent(definitions::add);
            }
        }
        return List.copyOf(definitions);
    }

    private java.util.Optional<PluginComponentDefinition> definition(String pluginId, Class<?> type) {
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.SignalSource.class)) return component(pluginId, PluginComponentKind.SIGNAL_SOURCE, type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.SignalSource.class).value(), type);
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.RootSignalSource.class)) return component(pluginId, PluginComponentKind.ROOT_SIGNAL_SOURCE, type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.RootSignalSource.class).value(), type);
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.RawSignalProcessor.class)) return component(pluginId, PluginComponentKind.RAW_SIGNAL_PROCESSOR, type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.RawSignalProcessor.class).value(), type);
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.SignalProcessor.class)) return component(pluginId, PluginComponentKind.SIGNAL_PROCESSOR, type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.SignalProcessor.class).value(), type);
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.SessionProcessor.class)) return component(pluginId, PluginComponentKind.SESSION_PROCESSOR, type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.SessionProcessor.class).value(), type);
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.SignalAdapter.class)) return component(pluginId, PluginComponentKind.SIGNAL_ADAPTER, type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.SignalAdapter.class).value(), type);
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Actor.class)) return component(pluginId, PluginComponentKind.ACTOR, type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Actor.class).value(), type);
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Action.class)) return component(pluginId, PluginComponentKind.ACTION, type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Action.class).value(), type);
        return java.util.Optional.empty();
    }

    private java.util.Optional<PluginComponentDefinition> component(String pluginId, PluginComponentKind kind, String name, Class<?> type) {
        Class<?> expected = switch (kind) {
            case SIGNAL_SOURCE -> io.github.actforever.kuudra.api.RawSignalSource.class;
            case ROOT_SIGNAL_SOURCE -> io.github.actforever.kuudra.api.RootSignalSource.class;
            case RAW_SIGNAL_PROCESSOR -> io.github.actforever.kuudra.api.RawSignalProcessor.class;
            case SIGNAL_PROCESSOR -> io.github.actforever.kuudra.api.SignalProcessor.class;
            case SESSION_PROCESSOR -> io.github.actforever.kuudra.api.SessionProcessor.class;
            case SIGNAL_ADAPTER -> io.github.actforever.kuudra.api.SignalAdapter.class;
            case ACTOR -> io.github.actforever.kuudra.api.Actor.class;
            case ACTION -> io.github.actforever.kuudra.api.Action.class;
        };
        if (!expected.isAssignableFrom(type)) throw new IllegalArgumentException(type.getName() + " annotated as " + kind + " but does not implement " + expected.getName());
        return java.util.Optional.of(new PluginComponentDefinition(pluginId, kind, name, type));
    }
}
