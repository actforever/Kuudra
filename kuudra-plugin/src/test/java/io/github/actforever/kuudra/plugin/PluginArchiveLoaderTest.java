package io.github.actforever.kuudra.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginArchiveLoaderTest {
    @TempDir Path directory;

    @Test
    void dependentPluginLinksParentClassesAndEnumeratesParentResources() throws Exception {
        Path baseClasses = compile("base", Map.of(
                "base.ParentType", """
                        package base;
                        public record ParentType(String key, boolean pressed) {
                            public static String message() { return "from-parent"; }
                        }
                        """,
                "base.ParentInterpreter", """
                        package base;
                        @io.github.actforever.kuudra.plugin.annotation.EventInterpreter("parent-interpreter")
                        @io.github.actforever.kuudra.plugin.annotation.ComponentDoc(
                            purpose = "Recognizes a parent sequence",
                            usageExample = "windowMillis: 100",
                            lifecyclePhases = {"start", "stop"},
                            configuration = {@io.github.actforever.kuudra.plugin.annotation.SpecProperty(
                                path = "windowMillis", type = Long.class, required = true,
                                description = "Sequence matching window", examples = {"100", "250"}),
                                @io.github.actforever.kuudra.plugin.annotation.SpecProperty(
                                path = "rule", type = java.util.Map.class,
                                description = "Sequence matching rule",
                                examples = {"{\\\"keys\\\":[\\\"A\\\",\\\"B\\\"],\\\"ordered\\\":true}"})},
                            emittedEvents = @io.github.actforever.kuudra.plugin.annotation.EventEmission(
                                stage = "sequence matched", eventType = "parent.matched",
                                description = "A recognized parent event", dataExample = "{key: A}"))
                        public final class ParentInterpreter implements io.github.actforever.kuudra.api.component.EventInterpreter {
                            public java.util.List<io.github.actforever.kuudra.api.event.KuudraEvent> interpret(
                                    io.github.actforever.kuudra.api.event.KuudraEvent event,
                                    io.github.actforever.kuudra.api.context.EventContext context) {
                                return java.util.List.of(event);
                            }
                        }
                        """,
                "base.BasePlugin", pluginSource("base", "BasePlugin", "base")), List.of());
        Path baseJar = jar("base.jar", baseClasses, metadata("base", "base.BasePlugin", List.of()),
                Map.of("base-resource.txt", "parent-resource"));

        Path childClasses = compile("child", Map.of(
                "child.ChildPlugin", """
                        package child;
                        import base.ParentType;
                        import io.github.actforever.kuudra.api.context.ContextCodecs;
                        import io.github.actforever.kuudra.plugin.KuudraPlugin;
                        import java.util.concurrent.CompletableFuture;
                        import java.util.concurrent.CompletionStage;
                        public final class ChildPlugin implements KuudraPlugin {
                            public String id() { return "child"; }
                            public CompletionStage<Void> start() { return CompletableFuture.completedFuture(null); }
                            public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }
                            public String parentMessage() { return ParentType.message(); }
                            public ParentType roundTrip() {
                                Object encoded = ContextCodecs.defaultCodec().encode(new ParentType("A", true));
                                return ContextCodecs.defaultCodec().decode(encoded, ParentType.class);
                            }
                        }
                        """), List.of(baseJar));
        Path childJar = jar("child.jar", childClasses, metadata("child", "child.ChildPlugin", List.of("base")), Map.of());

        List<PluginArchiveLoader.LoadedArchive> archives = new PluginArchiveLoader().loadAll(
                List.of(childJar, baseJar), PluginArchiveLoaderTest.class.getClassLoader());
        try {
            Map<String, PluginArchiveLoader.LoadedArchive> byId = archives.stream().collect(Collectors.toMap(
                    archive -> archive.plugin().metadata().id(), archive -> archive));
            PluginArchiveLoader.LoadedArchive base = byId.get("base");
            PluginArchiveLoader.LoadedArchive child = byId.get("child");

            PluginComponentDefinition interpreter = base.plugin().components().stream().filter(component ->
                    component.reference().equals("event-interpreter/base/parent-interpreter")).findFirst().orElseThrow();
            assertEquals("Recognizes a parent sequence", interpreter.documentation().purpose());
            assertEquals(List.of("RUNNING", "STOPPED"), interpreter.documentation().supportedDesiredStates());
            assertEquals("windowMillis", interpreter.documentation().configuration().get(0).path());
            assertEquals("java.lang.Long", interpreter.documentation().configuration().get(0).type());
            assertTrue(interpreter.documentation().configuration().get(0).required());
            assertEquals(List.of(100, 250), interpreter.documentation().configuration().get(0).examples());
            assertEquals(Map.of("keys", List.of("A", "B"), "ordered", true),
                    interpreter.documentation().configuration().get(1).examples().get(0));
            assertEquals("parent.matched", interpreter.documentation().emittedEvents().get(0).eventType());
            assertSame(base.classLoader().loadClass("base.ParentType"), child.classLoader().loadClass("base.ParentType"));
            assertEquals("from-parent", child.plugin().instance().getClass().getMethod("parentMessage").invoke(child.plugin().instance()));
            Object restored = child.plugin().instance().getClass().getMethod("roundTrip").invoke(child.plugin().instance());
            assertSame(base.classLoader().loadClass("base.ParentType"), restored.getClass());
            assertEquals("A", restored.getClass().getMethod("key").invoke(restored));
            assertEquals(true, restored.getClass().getMethod("pressed").invoke(restored));
            assertEquals("parent-resource", new String(child.classLoader().getResourceAsStream("base-resource.txt").readAllBytes()));
            assertEquals(1, child.classLoader().resources("base-resource.txt").count());
        } finally {
            for (PluginArchiveLoader.LoadedArchive archive : archives) archive.close();
        }
    }

    @Test
    void validatesMandatoryIdentityAndVersionRangeBeforeLoadingClasses() throws Exception {
        Path baseClasses = compile("version-base", Map.of("base.VersionPlugin", pluginSource("base", "VersionPlugin", "base")), List.of());
        Path baseJar = jar("version-base.jar", baseClasses, metadata("base", "base.VersionPlugin", "official", "1.2.0", List.of()), Map.of());
        Path childClasses = compile("version-child", Map.of("child.VersionPlugin", pluginSource("child", "VersionPlugin", "child")), List.of());
        PluginDependency incompatible = new PluginDependency("official", "base", true, "[2.0.0,3.0.0)");
        Path childJar = jar("version-child.jar", childClasses,
                metadata("child", "child.VersionPlugin", "official", "1.0.0", List.of(incompatible)), Map.of());

        IOException mismatch = assertThrows(IOException.class, () -> new PluginArchiveLoader().loadAll(
                List.of(baseJar, childJar), PluginArchiveLoaderTest.class.getClassLoader()));
        assertTrue(mismatch.getMessage().contains("version mismatch"));

        PluginDependency optional = new PluginDependency("official", "absent", false, "[1.0.0,2.0.0)");
        Path optionalJar = jar("optional.jar", childClasses,
                metadata("child", "child.VersionPlugin", "official", "1.0.0", List.of(optional)), Map.of());
        List<PluginArchiveLoader.LoadedArchive> loaded = new PluginArchiveLoader().loadAll(
                List.of(optionalJar), PluginArchiveLoaderTest.class.getClassLoader());
        loaded.forEach(archive -> { try { archive.close(); } catch (IOException error) { throw new RuntimeException(error); } });
    }

    @Test
    void resolvesDependenciesProvidedByCodeLevelPlugins() throws Exception {
        Path childClasses = compile("provided-child", Map.of(
                "child.ProvidedPlugin", pluginSource("child", "ProvidedPlugin", "child")), List.of());
        PluginDependency dependency = new PluginDependency("kuudra-official", "default", true, "[0.1.0,0.2.0)");
        Path childJar = jar("provided-child.jar", childClasses,
                metadata("child", "child.ProvidedPlugin", "demo", "1.0.0", List.of(dependency)), Map.of());
        PluginMetadata provided = new PluginMetadata(
                "default", "kuudra-official", "0.1.0", "provided.by.parent.ClassLoader", List.of());

        List<PluginArchiveLoader.LoadedArchive> loaded = new PluginArchiveLoader().loadAll(
                List.of(childJar), PluginArchiveLoaderTest.class.getClassLoader(), List.of(provided));
        try {
            assertEquals("child", loaded.get(0).plugin().metadata().id());
        } finally {
            for (PluginArchiveLoader.LoadedArchive archive : loaded) archive.close();
        }
    }

    @Test
    void loadsEqualPluginIdsFromDifferentNamespaces() throws Exception {
        Path alphaClasses = compile("alpha-shared", Map.of(
                "alpha.SharedPlugin", pluginSource("alpha", "SharedPlugin", "shared")), List.of());
        Path betaClasses = compile("beta-shared", Map.of(
                "beta.SharedPlugin", pluginSource("beta", "SharedPlugin", "shared")), List.of());
        Path alpha = jar("alpha-shared.jar", alphaClasses,
                metadata("shared", "alpha.SharedPlugin", "alpha", "1.0.0", List.of()), Map.of());
        Path beta = jar("beta-shared.jar", betaClasses,
                metadata("shared", "beta.SharedPlugin", "beta", "1.0.0", List.of()), Map.of());

        List<PluginArchiveLoader.LoadedArchive> loaded = new PluginArchiveLoader().loadAll(
                List.of(alpha, beta), PluginArchiveLoaderTest.class.getClassLoader());
        try {
            assertEquals(java.util.Set.of("alpha/shared", "beta/shared"), loaded.stream()
                    .map(item -> item.plugin().metadata().namespace() + "/" + item.plugin().metadata().id())
                    .collect(java.util.stream.Collectors.toSet()));
        } finally {
            for (PluginArchiveLoader.LoadedArchive archive : loaded) archive.close();
        }
    }

    private Path compile(String name, Map<String, String> sources, List<Path> dependencies) throws IOException {
        Path sourceRoot = Files.createDirectories(directory.resolve(name + "-src"));
        Path classes = Files.createDirectories(directory.resolve(name + "-classes"));
        List<Path> files = new java.util.ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceRoot.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "Tests require a JDK compiler");
        String classpath = dependencies.stream().map(Path::toString).collect(Collectors.joining(
                java.io.File.pathSeparator, "", dependencies.isEmpty() ? "" : java.io.File.pathSeparator)) + System.getProperty("java.class.path");
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            boolean compiled = compiler.getTask(null, manager, null, List.of("-classpath", classpath, "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromPaths(files)).call();
            assertTrue(compiled, "Fixture plugin compilation failed");
        }
        return classes;
    }

    private Path jar(String name, Path classes, String metadata, Map<String, String> resources) throws IOException {
        Path archive = directory.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry("META-INF/kuudra-plugin/metadata.toml"));
            output.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                output.putNextEntry(new JarEntry(resource.getKey()));
                output.write(resource.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }

    private static String pluginSource(String packageName, String className, String id) {
        return "package " + packageName + ";\n"
                + "import io.github.actforever.kuudra.plugin.KuudraPlugin;\n"
                + "import java.util.concurrent.*;\n"
                + "public final class " + className + " implements KuudraPlugin {\n"
                + " public String id() { return \"" + id + "\"; }\n"
                + " public CompletionStage<Void> start() { return CompletableFuture.completedFuture(null); }\n"
                + " public CompletionStage<Void> stop() { return CompletableFuture.completedFuture(null); }\n"
                + "}\n";
    }

    private static String metadata(String id, String entrypoint, List<String> dependencies) {
        return metadata(id, entrypoint, id, "1.0.0", dependencies.stream()
                .map(value -> new PluginDependency(value, value, true, "[1.0.0,2.0.0)")).toList());
    }

    private static String metadata(String id, String entrypoint, String namespace, String version, List<PluginDependency> dependencies) {
        StringBuilder result = new StringBuilder("id = \"").append(id).append("\"\nnamespace = \"").append(namespace)
                .append("\"\nversion = \"").append(version).append("\"\nentrypoint = \"").append(entrypoint).append("\"\n");
        for (PluginDependency dependency : dependencies) result.append("\n[[dependencies]]\nnamespace = \"")
                .append(dependency.namespace()).append("\"\npluginId = \"").append(dependency.pluginId())
                .append("\"\nmandatory = ").append(dependency.mandatory()).append("\nversionRange = \"")
                .append(dependency.versionRange()).append("\"\n");
        return result.toString();
    }
}
