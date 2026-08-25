package io.github.actforever.kuudra.config;

import io.github.actforever.kuudra.api.session.SessionGroupScope;
import io.github.actforever.kuudra.api.session.SessionSchedulingPolicy;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Format-neutral configuration model compiled by YAML, JSON or TOML readers.
 * Parsers intentionally live behind this model; the kernel never consumes a format directly.
 */
public final class KuudraConfig {
    private KuudraConfig() { }

    /** Aggregate produced from config.yaml and manifests/. */
    public record RuntimeConfig(RuntimeSettings runtime, ResourceSelectionSettings resourceSelection,
                                ReconciliationSettings reconciliation,
                                StateStoreSettings stateStore, LoggingSettings logging, I18nSettings i18n, Path homeDirectory,
                                Map<String, Object> globalContext, KuudraManifest.Resources manifests) {
        public RuntimeConfig {
            if (runtime == null || resourceSelection == null || reconciliation == null || stateStore == null || logging == null || i18n == null || manifests == null) {
                throw new IllegalArgumentException("runtime, resourceSelection, reconciliation, stateStore, logging, i18n, and manifests must not be null");
            }
            homeDirectory = homeDirectory.toAbsolutePath().normalize();
            globalContext = Map.copyOf(globalContext);
        }
    }
    public record RuntimeSettings(int queueCapacity, int workerThreads, int maxEventHops,
                                  int dispatcherPollIntervalMs, int shutdownSessionDrainTimeoutMs,
                                  SessionCoordinatorSettings sessionCoordinator) {
        public RuntimeSettings {
            if (queueCapacity < 1 || workerThreads < 1 || maxEventHops < 1
                    || dispatcherPollIntervalMs < 1 || shutdownSessionDrainTimeoutMs < 0) {
                throw new IllegalArgumentException("runtime capacities and timeouts must be valid");
            }
            if (sessionCoordinator == null) throw new IllegalArgumentException("sessionCoordinator must not be null");
        }
    }
    public enum NamespaceMode { ALL, INCLUDE }
    public record ResourceSelectionSettings(NamespaceMode namespaceMode, Set<String> namespaces) {
        public ResourceSelectionSettings {
            if (namespaceMode == null) throw new IllegalArgumentException("resourceSelection.namespaceMode must not be null");
            namespaces = Set.copyOf(namespaces);
            if (namespaces.stream().anyMatch(namespace -> namespace == null || namespace.isBlank())) {
                throw new IllegalArgumentException("resourceSelection.namespaces must contain non-blank names");
            }
            if (namespaceMode == NamespaceMode.INCLUDE && namespaces.isEmpty()) {
                throw new IllegalArgumentException("resourceSelection.namespaces must not be empty in INCLUDE mode");
            }
        }
        public boolean selects(String namespace) {
            return namespaceMode == NamespaceMode.ALL || namespaces.contains(namespace);
        }
    }
    public record ReconciliationSettings(boolean enabled, int intervalMs) {
        public ReconciliationSettings { if (intervalMs < 1) throw new IllegalArgumentException("reconciliation.intervalMs must be positive"); }
    }
    public record StateStoreSettings(int busyTimeoutMs) {
        public StateStoreSettings { if (busyTimeoutMs < 0) throw new IllegalArgumentException("stateStore.busyTimeoutMs must not be negative"); }
    }
    public record SessionCoordinatorSettings(SessionSchedulingPolicy defaultPolicy,
                                             SessionGroupScope defaultGroupScope,
                                             int maxParallelSessions, int queueCapacity) {
        public SessionCoordinatorSettings {
            if (defaultPolicy == null || defaultGroupScope == null) throw new IllegalArgumentException("session coordinator defaults must not be null");
            if (maxParallelSessions < 1 || queueCapacity < 0) throw new IllegalArgumentException("invalid session coordinator capacities");
        }
    }
    public record LoggingSettings(String level, boolean consoleEnabled, boolean fileEnabled) {
        public LoggingSettings {
            if (level == null || level.isBlank()) throw new IllegalArgumentException("logging level must not be blank");
        }
    }
    public record I18nSettings(String preferredLocale) {
        public I18nSettings {
            if (preferredLocale == null || !preferredLocale.matches("[a-z]{2}_[A-Z]{2}")) {
                throw new IllegalArgumentException("i18n.preferredLocale must use xx_XX format");
            }
        }
    }

    public record EdgeConfig(String from, String to) {
        public EdgeConfig { if (from == null || from.isBlank() || to == null || to.isBlank()) throw new IllegalArgumentException("edge endpoints must not be blank"); }
    }
}
