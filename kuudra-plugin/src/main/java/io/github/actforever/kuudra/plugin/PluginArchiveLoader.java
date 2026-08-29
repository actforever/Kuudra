package io.github.actforever.kuudra.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarFile;

/** Loads a metadata-declared Java plugin from an isolated child class loader. */
public final class PluginArchiveLoader {
    public LoadedArchive load(Path archive, ClassLoader parent) throws IOException {
        return loadAll(List.of(archive), parent).get(0);
    }

    /** Loads one dependency graph so dependent plugin classes are visible through declared metadata dependencies. */
    public List<LoadedArchive> loadAll(List<Path> archives, ClassLoader parent) throws IOException {
        return loadAll(archives, parent, List.of());
    }

    /** Loads archives while accepting code-level plugins already visible from the parent ClassLoader. */
    public List<LoadedArchive> loadAll(List<Path> archives, ClassLoader parent, List<PluginMetadata> providedPlugins) throws IOException {
        Objects.requireNonNull(parent, "parent");
        LinkedHashMap<String, PluginMetadata> provided = new LinkedHashMap<>();
        for (PluginMetadata metadata : providedPlugins) {
            if (provided.putIfAbsent(identity(metadata), metadata) != null) throw new IOException("Duplicate provided plugin identity: " + identity(metadata));
        }
        LinkedHashMap<String, ArchiveDefinition> definitions = new LinkedHashMap<>();
        for (Path archive : archives) {
            Path normalized = normalizedArchive(archive);
            PluginMetadata metadata = readMetadata(normalized);
            String identity = identity(metadata);
            if (provided.containsKey(identity)) throw new IOException("Plugin identity conflicts with provided plugin: " + identity);
            if (definitions.putIfAbsent(identity, new ArchiveDefinition(normalized, metadata)) != null) {
                throw new IOException("Duplicate plugin identity in archives: " + identity);
            }
        }
        validateDependencies(definitions, provided);
        LinkedHashMap<String, LoadedArchive> loaded = new LinkedHashMap<>();
        try {
            for (String id : definitions.keySet()) load(id, definitions, loaded, new ArrayList<>(), parent);
            return List.copyOf(loaded.values());
        } catch (IOException | RuntimeException error) {
            for (LoadedArchive archive : loaded.values()) try { archive.close(); } catch (IOException close) { error.addSuppressed(close); }
            throw error;
        }
    }

    private LoadedArchive load(String id, LinkedHashMap<String, ArchiveDefinition> definitions, LinkedHashMap<String, LoadedArchive> loaded,
                               List<String> visiting, ClassLoader parent) throws IOException {
        LoadedArchive existing = loaded.get(id);
        if (existing != null) return existing;
        if (visiting.contains(id)) throw new IOException("Plugin dependency cycle: " + visiting + " -> " + id);
        ArchiveDefinition definition = definitions.get(id);
        if (definition == null) throw new IOException("Declared plugin dependency archive is missing: " + id);
        visiting.add(id);
        List<DependencyPluginClassLoader> dependencies = new ArrayList<>();
        for (PluginDependency dependency : definition.metadata.dependencies()) {
            if (definitions.containsKey(dependency.identity()))
                dependencies.add((DependencyPluginClassLoader) load(dependency.identity(), definitions, loaded, visiting, parent).classLoader());
        }
        visiting.remove(visiting.size() - 1);
        DependencyPluginClassLoader classLoader = new DependencyPluginClassLoader(definition.archive.toUri().toURL(), parent, dependencies);
        try {
            Class<?> entrypoint = Class.forName(definition.metadata.entrypoint(), true, classLoader);
            if (!KuudraPlugin.class.isAssignableFrom(entrypoint)) throw new IOException("Plugin entrypoint does not implement KuudraPlugin: " + definition.metadata.entrypoint());
            KuudraPlugin plugin = (KuudraPlugin) entrypoint.getDeclaredConstructor().newInstance();
            if (!plugin.id().equals(definition.metadata.id())) throw new IOException("Plugin id does not match metadata: " + definition.metadata.id());
            List<ResourceTemplateDefinition> templates = new ResourceTemplateScanner().scan(
                    definition.archive, classLoader, definition.metadata.id(), definition.metadata.namespace());
            LoadedArchive result = new LoadedArchive(definition.archive, classLoader,
                    new LoadedPlugin(definition.metadata, plugin, templates));
            loaded.put(id, result);
            return result;
        } catch (IOException error) {
            try { classLoader.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        } catch (ReflectiveOperationException | RuntimeException error) {
            try { classLoader.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw new IOException("Cannot load plugin archive " + definition.archive, error);
        }
    }

    private static Path normalizedArchive(Path archive) throws IOException {
        Path normalized = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !normalized.getFileName().toString().endsWith(".jar")) throw new IOException("Plugin archive must be a readable JAR: " + normalized);
        return normalized;
    }
    private static void validateDependencies(Map<String, ArchiveDefinition> definitions, Map<String, PluginMetadata> provided) throws IOException {
        for (ArchiveDefinition owner : definitions.values()) for (PluginDependency dependency : owner.metadata.dependencies()) {
            ArchiveDefinition target = definitions.get(dependency.identity());
            PluginMetadata targetMetadata = target == null ? provided.get(dependency.identity()) : target.metadata;
            if (targetMetadata == null) {
                if (dependency.mandatory()) throw new IOException("Mandatory plugin dependency is missing: "
                        + owner.metadata.namespace() + "/" + owner.metadata.id() + " -> " + dependency.identity());
                continue;
            }
            if (!targetMetadata.namespace().equals(dependency.namespace())) throw new IOException("Plugin dependency namespace mismatch: expected "
                    + dependency.identity() + " but found " + targetMetadata.namespace() + "/" + targetMetadata.id());
            if (!dependency.accepts(targetMetadata.version())) throw new IOException("Plugin dependency version mismatch: "
                    + dependency.identity() + " requires " + dependency.versionRange() + " but found " + targetMetadata.version());
        }
    }
    private static String identity(PluginMetadata metadata) { return metadata.namespace() + "/" + metadata.id(); }
    private static PluginMetadata readMetadata(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) { return PluginMetadataToml.read(jar.getInputStream(jar.getJarEntry(PluginMetadataToml.PATH))); }
        catch (NullPointerException missing) { throw new IOException("Missing " + PluginMetadataToml.PATH + " in " + archive, missing); }
    }
    private record ArchiveDefinition(Path archive, PluginMetadata metadata) { }

    public record LoadedArchive(Path archive, URLClassLoader classLoader, LoadedPlugin plugin) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            classLoader.close();
        }
    }

    public record LoadedPlugin(PluginMetadata metadata, KuudraPlugin instance,
                               List<ResourceTemplateDefinition> resourceTemplates) {
        public LoadedPlugin { resourceTemplates = List.copyOf(resourceTemplates); }
    }
}
