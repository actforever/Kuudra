package io.github.actforever.kuudra.plugin;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** Discovers annotation-declared components from a plugin archive. */
final class PluginComponentScanner {
    static final String INDEX_PATH = "META-INF/kuudra-plugin/components.idx";
    List<PluginComponentDefinition> scan(Path archive, URLClassLoader loader, String pluginId, String namespace) throws IOException {
        List<PluginComponentDefinition> definitions = new ArrayList<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            var index = jar.getJarEntry(INDEX_PATH);
            if (index != null) {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(jar.getInputStream(index), java.nio.charset.StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null;) {
                        String className = line.strip();
                        if (className.isEmpty() || className.startsWith("#")) continue;
                        definition(pluginId, namespace, load(className, loader)).ifPresent(definitions::add);
                    }
                }
                return List.copyOf(definitions);
            }
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                // Multi-release entries are implementation variants selected by the JVM's
                // ClassLoader. Their archive path is not a legal binary class name and must
                // never be inspected as an independent plugin component.
                if (!name.endsWith(".class") || name.equals("module-info.class")
                        || name.startsWith("META-INF/versions/")) continue;
                Class<?> type = load(name.substring(0, name.length() - 6).replace('/', '.'), loader);
                definition(pluginId, namespace, type).ifPresent(definitions::add);
            }
        }
        return List.copyOf(definitions);
    }

    private static Class<?> load(String className, URLClassLoader loader) throws IOException {
        try { return Class.forName(className, false, loader); }
        catch (ClassNotFoundException | LinkageError error) { throw new IOException("Cannot inspect plugin class " + className, error); }
    }

    private java.util.Optional<PluginComponentDefinition> definition(String pluginId, String namespace, Class<?> type) {
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventSource.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventSource.class); return component(pluginId, namespace, PluginComponentKind.EVENT_SOURCE, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventInterpreter.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventInterpreter.class); return component(pluginId, namespace, PluginComponentKind.EVENT_INTERPRETER, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventAdapter.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventAdapter.class); return component(pluginId, namespace, PluginComponentKind.EVENT_ADAPTER, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Ingress.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Ingress.class); return component(pluginId, namespace, PluginComponentKind.INGRESS, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventHandler.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventHandler.class); return component(pluginId, namespace, PluginComponentKind.EVENT_HANDLER, annotation.value(), type, annotation.instancePolicy()); }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Egress.class)) { var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Egress.class); return component(pluginId, namespace, PluginComponentKind.EGRESS, annotation.value(), type, annotation.instancePolicy()); }
        return java.util.Optional.empty();
    }

    private java.util.Optional<PluginComponentDefinition> component(String pluginId, String namespace, PluginComponentKind kind, String name, Class<?> type,
                                                                     io.github.actforever.kuudra.plugin.annotation.InstancePolicy policy) {
        Class<?> expected = switch (kind) {
            case EVENT_SOURCE -> io.github.actforever.kuudra.api.component.EventSource.class;
            case EVENT_INTERPRETER -> io.github.actforever.kuudra.api.component.EventInterpreter.class;
            case EVENT_ADAPTER -> io.github.actforever.kuudra.api.component.EventAdapter.class;
            case INGRESS -> io.github.actforever.kuudra.api.component.Ingress.class;
            case EVENT_HANDLER -> io.github.actforever.kuudra.api.component.EventHandler.class;
            case EGRESS -> io.github.actforever.kuudra.api.component.Egress.class;
        };
        if (!expected.isAssignableFrom(type)) throw new IllegalArgumentException(type.getName() + " annotated as " + kind + " but does not implement " + expected.getName());
        ComponentInstancePolicy instancePolicy = new ComponentInstancePolicy(policy.maxInstances(), policy.limitScope(),
                policy.exclusivityDomain().isBlank() ? namespace + "/" + name : policy.exclusivityDomain(), policy.threadSafe());
        return java.util.Optional.of(new PluginComponentDefinition(pluginId, namespace, kind, name, type, instancePolicy,
                documentation(type)));
    }

    private PluginComponentDocumentation documentation(Class<?> type) {
        var annotation = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.ComponentDoc.class);
        boolean lifecycle = io.github.actforever.kuudra.api.lifecycle.Lifecycle.class.isAssignableFrom(type)
                || PluginComponentLifecycle.class.isAssignableFrom(type);
        List<String> desiredStates = supportedDesiredStates(type);
        if (annotation == null) return new PluginComponentDocumentation("", lifecycle, List.of(), desiredStates, List.of(), List.of());
        List<PluginConfigurationDocumentation> configuration = java.util.Arrays.stream(annotation.configuration())
                .map(item -> new PluginConfigurationDocumentation(item.path(), typeName(item.type()), item.required(),
                        item.defaultValue(), item.description(), parseExamples(item.examples()), List.of(item.allowedValues())))
                .toList();
        List<PluginEventDocumentation> events = java.util.Arrays.stream(annotation.emittedEvents())
                .map(item -> new PluginEventDocumentation(item.stage(), item.eventType(), item.description(), item.dataExample()))
                .toList();
        return new PluginComponentDocumentation(annotation.purpose(), lifecycle,
                List.of(annotation.lifecyclePhases()), desiredStates, configuration, events);
    }

    private static String typeName(Class<?> type) {
        if (!type.isArray()) return type.getName();
        return typeName(type.getComponentType()) + "[]";
    }

    private static List<Object> parseExamples(String[] examples) {
        return java.util.Arrays.stream(examples)
                .map(io.github.actforever.kuudra.api.context.ContextCodecs.defaultCodec()::parseLiteral)
                .toList();
    }

    private static List<String> supportedDesiredStates(Class<?> type) {
        if (io.github.actforever.kuudra.api.lifecycle.PausableLifecycle.class.isAssignableFrom(type)) {
            return List.of("RUNNING", "PAUSED", "STOPPED");
        }
        if (io.github.actforever.kuudra.api.lifecycle.Lifecycle.class.isAssignableFrom(type)) {
            return List.of("RUNNING", "STOPPED");
        }
        return List.of("ACTIVE", "INACTIVE");
    }
}
