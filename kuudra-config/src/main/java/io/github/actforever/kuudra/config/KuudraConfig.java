package io.github.actforever.kuudra.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Format-neutral configuration model compiled by YAML, JSON or TOML readers.
 * Parsers intentionally live behind this model; the kernel never consumes a format directly.
 */
public final class KuudraConfig {
    private KuudraConfig() { }

    /** Aggregate produced from config.yaml and the definitions in flows/. */
    public record RuntimeConfig(RuntimeSettings runtime, LoggingSettings logging, Path homeDirectory, Map<String, Object> globalContext, Map<String, FlowConfig> flows) {
        public RuntimeConfig {
            if (runtime == null || logging == null) throw new IllegalArgumentException("runtime and logging settings must not be null");
            homeDirectory = homeDirectory.toAbsolutePath().normalize();
            globalContext = Map.copyOf(globalContext);
            flows = Map.copyOf(flows);
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

    /** Compose-style Flow scope: named components plus their routing graph. */
    public record FlowConfig(String id, Map<String, NodeConfig> nodes, List<EdgeConfig> edges, List<SourceBinding> sources) {
        public FlowConfig {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("flow id must not be blank");
            nodes = Map.copyOf(nodes); edges = List.copyOf(edges); sources = List.copyOf(sources);
        }
    }

    /** type is event-adapter, event-processor, session-allocator, or actor. */
    public record NodeConfig(String id, String type, String component, Map<String, Object> options) {
        public NodeConfig {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("node id must not be blank");
            if (type == null || type.isBlank()) throw new IllegalArgumentException("node type must not be blank");
            if (!type.equals("session-allocator") && (component == null || component.isBlank())) throw new IllegalArgumentException("component must not be blank");
            options = Map.copyOf(options);
        }
    }
    public record EdgeConfig(String from, String to) {
        public EdgeConfig { if (from == null || from.isBlank() || to == null || to.isBlank()) throw new IllegalArgumentException("edge endpoints must not be blank"); }
    }
    /** A named EventSource resource in a Flow scope. */
    public record SourceBinding(String id, String component, String targetNodeId, boolean enabled) {
        public SourceBinding {
            if (id == null || id.isBlank() || component == null || component.isBlank() || targetNodeId == null || targetNodeId.isBlank()) throw new IllegalArgumentException("source binding values must not be blank");
        }
        public SourceBinding(String component, String targetNodeId) { this(component, component, targetNodeId, true); }
    }
}
