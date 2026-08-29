package io.github.actforever.kuudra.plugin;

import io.github.actforever.kuudra.api.event.EventHandlerContext;
import io.github.actforever.kuudra.api.event.KuudraEvent;
import io.github.actforever.kuudra.plugin.annotation.ResourceDoc;
import io.github.actforever.kuudra.plugin.annotation.SpecProperty;

import java.io.*;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.jar.JarFile;

/** Discovers and validates ResourceTemplates from a plugin archive. */
final class ResourceTemplateScanner {
    static final String INDEX_PATH = "META-INF/kuudra-plugin/resources.idx";

    List<ResourceTemplateDefinition> scan(Path archive, URLClassLoader loader,
                                          String pluginId, String namespace) throws IOException {
        List<ResourceTemplateDefinition> definitions = new ArrayList<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            var index = jar.getJarEntry(INDEX_PATH);
            if (index != null) {
                try (var reader = new BufferedReader(new InputStreamReader(
                        jar.getInputStream(index), StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        String className = line.strip();
                        if (!className.isEmpty() && !className.startsWith("#")) {
                            definition(pluginId, namespace, load(className, loader)).ifPresent(definitions::add);
                        }
                    }
                }
                return List.copyOf(definitions);
            }
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.equals("module-info.class")
                        || name.startsWith("META-INF/versions/")) continue;
                definition(pluginId, namespace,
                        load(name.substring(0, name.length() - 6).replace('/', '.'), loader))
                        .ifPresent(definitions::add);
            }
        }
        return List.copyOf(definitions);
    }

    private static Class<?> load(String className, URLClassLoader loader) throws IOException {
        try { return Class.forName(className, false, loader); }
        catch (ClassNotFoundException | LinkageError error) {
            throw new IOException("Cannot inspect plugin class " + className, error);
        }
    }

    private Optional<ResourceTemplateDefinition> definition(String pluginId, String namespace, Class<?> type) {
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Controller.class)) {
            var a = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Controller.class);
            return Optional.of(template(pluginId, namespace, ResourceTemplateKind.CONTROLLER,
                    a.value(), type, policy(a.policy()), handlers(type)));
        }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventSource.class)) {
            var a = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventSource.class);
            return Optional.of(template(pluginId, namespace, ResourceTemplateKind.EVENT_SOURCE,
                    a.value(), type, policy(a.policy()), List.of()));
        }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventInterpreter.class)) {
            var a = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventInterpreter.class);
            return Optional.of(template(pluginId, namespace, ResourceTemplateKind.EVENT_INTERPRETER,
                    a.value(), type, policy(a.policy()), List.of()));
        }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.EventAdapter.class)) {
            var a = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventAdapter.class);
            return Optional.of(template(pluginId, namespace, ResourceTemplateKind.EVENT_ADAPTER,
                    a.value(), type, policy(a.policy()), List.of()));
        }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Ingress.class)) {
            var a = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Ingress.class);
            return Optional.of(template(pluginId, namespace, ResourceTemplateKind.INGRESS,
                    a.value(), type, policy(a.policy()), List.of()));
        }
        if (type.isAnnotationPresent(io.github.actforever.kuudra.plugin.annotation.Egress.class)) {
            var a = type.getAnnotation(io.github.actforever.kuudra.plugin.annotation.Egress.class);
            return Optional.of(template(pluginId, namespace, ResourceTemplateKind.EGRESS,
                    a.value(), type, policy(a.policy()), List.of()));
        }
        return Optional.empty();
    }

    private ResourceTemplateDefinition template(String pluginId, String namespace, ResourceTemplateKind kind,
                                                String name, Class<?> type, ResourcePolicy policy,
                                                List<ControllerHandlerDefinition> handlers) {
        if (!ResourceLifecycle.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getName() + " must implement " + ResourceLifecycle.class.getName());
        }
        Class<?> contract = switch (kind) {
            case EVENT_SOURCE -> io.github.actforever.kuudra.api.component.EventSource.class;
            case EVENT_INTERPRETER -> io.github.actforever.kuudra.api.component.EventInterpreter.class;
            case EVENT_ADAPTER -> io.github.actforever.kuudra.api.component.EventAdapter.class;
            case INGRESS -> io.github.actforever.kuudra.api.component.Ingress.class;
            case EGRESS -> io.github.actforever.kuudra.api.component.Egress.class;
            case CONTROLLER -> null;
        };
        if (contract != null && !contract.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getName() + " annotated as " + kind
                    + " but does not implement " + contract.getName());
        }
        return new ResourceTemplateDefinition(pluginId, namespace, kind, name, type, policy,
                documentation(type), handlers);
    }

    private List<ControllerHandlerDefinition> handlers(Class<?> type) {
        List<ControllerHandlerDefinition> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            var a = method.getAnnotation(io.github.actforever.kuudra.plugin.annotation.EventHandler.class);
            if (a == null) continue;
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException("Controller handler must be public and non-static: " + method);
            }
            if (!Arrays.equals(method.getParameterTypes(), new Class<?>[]{KuudraEvent.class, EventHandlerContext.class})
                    || !CompletionStage.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalArgumentException("Controller handler signature must be (KuudraEvent, "
                        + "EventHandlerContext) -> CompletionStage<Void>: " + method);
            }
            if (!names.add(a.value())) {
                throw new IllegalArgumentException("Duplicate Controller handler name " + a.value()
                        + " in " + type.getName());
            }
            result.add(new ControllerHandlerDefinition(a.value(), method, a.purpose(),
                    properties(a.arguments()), events(a.emittedEvents())));
        }
        return List.copyOf(result);
    }

    private ResourceTemplateDocumentation documentation(Class<?> type) {
        ResourceDoc a = type.getAnnotation(ResourceDoc.class);
        if (a == null) return ResourceTemplateDocumentation.EMPTY;
        return new ResourceTemplateDocumentation(a.purpose(), List.of(a.lifecyclePhases()),
                properties(a.options()), properties(a.arguments()), events(a.emittedEvents()));
    }

    private static ResourcePolicy policy(io.github.actforever.kuudra.plugin.annotation.ResourcePolicy value) {
        return new ResourcePolicy(value.maxInstances(), value.limitScope(), value.exclusivityDomain(),
                value.allowParallel());
    }

    private static List<PluginConfigurationDocumentation> properties(SpecProperty[] values) {
        return Arrays.stream(values).map(item -> new PluginConfigurationDocumentation(item.path(),
                typeName(item.type()), item.required(), item.defaultValue(), item.description(),
                Arrays.stream(item.examples()).map(io.github.actforever.kuudra.api.context.ContextCodecs
                        .defaultCodec()::parseLiteral).toList(), List.of(item.allowedValues()))).toList();
    }

    private static List<PluginEventDocumentation> events(
            io.github.actforever.kuudra.plugin.annotation.EventEmission[] values) {
        return Arrays.stream(values).map(item -> new PluginEventDocumentation(item.stage(), item.eventType(),
                item.description(), item.dataExample())).toList();
    }

    private static String typeName(Class<?> type) {
        return type.isArray() ? typeName(type.getComponentType()) + "[]" : type.getName();
    }
}
