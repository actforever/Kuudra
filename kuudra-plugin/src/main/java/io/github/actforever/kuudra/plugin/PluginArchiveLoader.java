package io.github.actforever.kuudra.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Loads a metadata-declared Java plugin from an isolated child class loader. */
public final class PluginArchiveLoader {
    public LoadedArchive load(Path archive, ClassLoader parent) throws IOException {
        Path normalized = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !normalized.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Plugin archive must be a readable JAR: " + normalized);
        }
        URLClassLoader classLoader = new URLClassLoader(new URL[]{normalized.toUri().toURL()}, Objects.requireNonNull(parent, "parent"));
        try {
            PluginMetadata metadata = PluginMetadataToml.read(classLoader.getResourceAsStream(PluginMetadataToml.PATH));
            Class<?> entrypoint = Class.forName(metadata.entrypoint(), true, classLoader);
            if (!KuudraPlugin.class.isAssignableFrom(entrypoint)) throw new IOException("Plugin entrypoint does not implement KuudraPlugin: " + metadata.entrypoint());
            KuudraPlugin plugin = (KuudraPlugin) entrypoint.getDeclaredConstructor().newInstance();
            if (!plugin.id().equals(metadata.id())) throw new IOException("Plugin id does not match metadata: " + metadata.id());
            List<PluginComponentDefinition> components = new PluginComponentScanner().scan(normalized, classLoader, metadata.id(), metadata.namespace());
            return new LoadedArchive(normalized, classLoader, new LoadedPlugin(metadata, plugin, components));
        } catch (IOException error) {
            try { classLoader.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        } catch (ReflectiveOperationException | RuntimeException error) {
            try { classLoader.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw new IOException("Cannot load plugin archive " + normalized, error);
        }
    }

    public record LoadedArchive(Path archive, URLClassLoader classLoader, LoadedPlugin plugin) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            classLoader.close();
        }
    }

    public record LoadedPlugin(PluginMetadata metadata, KuudraPlugin instance, List<PluginComponentDefinition> components) {
        public LoadedPlugin { components = List.copyOf(components); }
    }
}
