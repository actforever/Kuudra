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
                "base.BasePlugin", pluginSource("base", "BasePlugin", "base")), List.of());
        Path baseJar = jar("base.jar", baseClasses, metadata("base", "base.BasePlugin", List.of()),
                Map.of("base-resource.txt", "parent-resource"));

        Path childClasses = compile("child", Map.of(
                "child.ChildPlugin", """
                        package child;
                        import base.ParentType;
                        import io.github.actforever.kuudra.api.ContextCodecs;
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
        return "id = \"" + id + "\"\nnamespace = \"" + id + "\"\nversion = \"1.0.0\"\nentrypoint = \"" + entrypoint
                + "\"\ndependencies = [" + dependencies.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(", ")) + "]\n";
    }
}
