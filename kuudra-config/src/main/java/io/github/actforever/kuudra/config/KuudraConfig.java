package io.github.actforever.kuudra.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * Format-neutral configuration model compiled by YAML, JSON or TOML readers.
 * Parsers intentionally live behind this model; the kernel never consumes a format directly.
 */
public final class KuudraConfig {
    private KuudraConfig() { }

    /** Aggregate produced from config.yaml and manifests/. */
    public record RuntimeConfig(RuntimeSettings runtime, LoggingSettings logging, Path homeDirectory,
                                Map<String, Object> globalContext, KuudraManifest.Resources manifests) {
        public RuntimeConfig {
            if (runtime == null || logging == null || manifests == null) throw new IllegalArgumentException("runtime, logging, and manifests must not be null");
            homeDirectory = homeDirectory.toAbsolutePath().normalize();
            globalContext = Map.copyOf(globalContext);
        }
    }
    public record RuntimeSettings(int queueCapacity, int workerThreads) {
        public RuntimeSettings { if (queueCapacity < 1 || workerThreads < 1) throw new IllegalArgumentException("runtime capacities must be positive"); }
    }
    public record LoggingSettings(String level, boolean consoleEnabled, boolean fileEnabled) {
        public LoggingSettings {
            if (level == null || level.isBlank()) throw new IllegalArgumentException("logging level must not be blank");
        }
    }

    public record EdgeConfig(String from, String to) {
        public EdgeConfig { if (from == null || from.isBlank() || to == null || to.isBlank()) throw new IllegalArgumentException("edge endpoints must not be blank"); }
    }
}
