package io.github.actforever.kuudra.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/** Loads Java plugin providers from a plugin JAR using an isolated child class loader. */
public final class PluginArchiveLoader {
    public LoadedArchive load(Path archive, ClassLoader parent) throws IOException {
        Path normalized = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !normalized.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Plugin archive must be a readable JAR: " + normalized);
        }
        URLClassLoader classLoader = new URLClassLoader(new URL[]{normalized.toUri().toURL()}, Objects.requireNonNull(parent, "parent"));
        try {
            List<KuudraPlugin> plugins = ServiceLoader.load(KuudraPlugin.class, classLoader).stream()
                    .map(ServiceLoader.Provider::get).toList();
            if (plugins.isEmpty()) throw new IOException("No KuudraPlugin provider in " + normalized);
            return new LoadedArchive(normalized, classLoader, plugins);
        } catch (RuntimeException | IOException error) {
            try { classLoader.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    public record LoadedArchive(Path archive, URLClassLoader classLoader, List<KuudraPlugin> plugins) implements AutoCloseable {
        public LoadedArchive {
            plugins = List.copyOf(plugins);
        }

        @Override
        public void close() throws IOException {
            classLoader.close();
        }
    }
}
