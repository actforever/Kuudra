package io.github.actforever.kuudra.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import io.github.actforever.kuudra.api.session.SessionGroupScope;
import io.github.actforever.kuudra.api.session.SessionSchedulingPolicy;

/** Reads config.yaml plus Flow YAML files into the format-neutral configuration model. */
public final class KuudraYamlLoader {
    private KuudraYamlLoader() { }

    public static KuudraConfig.RuntimeConfig load(Path file) throws IOException {
        return load(readResource(file));
    }

    public static KuudraConfigResource readResource(Path file) throws IOException {
        Path configFile = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (!Files.isRegularFile(configFile)) throw new IOException("Kuudra configuration does not exist: " + configFile);
        return new KuudraConfigResource(mapping(read(configFile), configFile), configFile.getParent(), configFile.toString());
    }

    public static KuudraConfigResource readResource(InputStream input, Path baseDirectory, String description) throws IOException {
        Objects.requireNonNull(input, "input");
        try (Reader reader = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)) {
            return new KuudraConfigResource(mapping(new Yaml().load(reader), description), baseDirectory, description);
        }
    }

    /** Deeply merges resources from lowest to highest priority using one path-resolution base. */
    public static KuudraConfigResource merge(Path baseDirectory, String description, KuudraConfigResource... resources) throws IOException {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (KuudraConfigResource resource : resources) {
            if (resource != null) mergeMappings(merged, resource.values());
        }
        return new KuudraConfigResource(merged, baseDirectory, description);
    }

    /** Compiles a framework-neutral configuration resource after its host has merged any overrides. */
    public static KuudraConfig.RuntimeConfig load(KuudraConfigResource resource) throws IOException {
        Objects.requireNonNull(resource, "resource");
        Path base = resource.baseDirectory();
        Map<String, Object> root = resource.values();
        if (root.containsKey("plugins")) throw new IOException("Configuration key 'plugins' has been removed; use <home-directory>/plugins");
        if (root.containsKey("flows-directory")) throw new IOException("Configuration key 'flows-directory' has been removed; use <home-directory>/manifests");
        Map<String, Object> runtime = optionalMapping(root, "runtime");
        int queueCapacity = integer(runtime, "queue-capacity", 1_024);
        int workerThreads = integer(runtime, "worker-threads", Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        int maxEventHops = integer(runtime, "max-event-hops", 256);
        int dispatcherPollIntervalMs = integer(runtime, "dispatcher-poll-interval-ms", 200);
        int shutdownSessionDrainTimeoutMs = nonNegativeInteger(runtime, "shutdown-session-drain-timeout-ms", 5_000);
        Map<String, Object> coordinator = optionalMapping(runtime, "session-coordinator");
        SessionSchedulingPolicy defaultPolicy = enumValue(coordinator, "default-policy", SessionSchedulingPolicy.PARALLEL, SessionSchedulingPolicy.class);
        SessionGroupScope defaultGroupScope = enumValue(coordinator, "default-group-scope", SessionGroupScope.FLOW_BINDING, SessionGroupScope.class);
        int maxParallelSessions = integer(coordinator, "max-parallel-sessions", 64);
        int sessionQueueCapacity = integer(coordinator, "queue-capacity", 256);
        Map<String, Object> resourceSelection = optionalMapping(root, "resource-selection");
        KuudraConfig.NamespaceMode namespaceMode = enumValue(resourceSelection, "namespace-mode",
                KuudraConfig.NamespaceMode.ALL, KuudraConfig.NamespaceMode.class);
        java.util.Set<String> selectedNamespaces = stringSet(resourceSelection.get("namespaces"), "resource-selection.namespaces");
        Map<String, Object> reconciliation = optionalMapping(root, "reconciliation");
        boolean reconciliationEnabled = bool(reconciliation.get("enabled"), true);
        int reconciliationIntervalMs = integer(reconciliation, "interval-ms", 1_000);
        Map<String, Object> stateStore = optionalMapping(root, "state-store");
        int stateStoreBusyTimeoutMs = nonNegativeInteger(stateStore, "busy-timeout-ms", 5_000);
        Map<String, Object> logging = optionalMapping(root, "logging");
        String loggingLevel = string(logging.getOrDefault("level", "info"), "logging.level").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF").contains(loggingLevel)) {
            throw new IOException("Unsupported logging.level: " + loggingLevel);
        }
        boolean consoleEnabled = bool(logging.get("console-enabled"), true);
        boolean fileEnabled = bool(logging.get("file-enabled"), true);
        Map<String, Object> i18n = optionalMapping(root, "i18n");
        String preferredLocale = string(i18n.getOrDefault("preferred-locale", "en_US"), "i18n.preferred-locale");
        if (!preferredLocale.matches("[a-z]{2}_[A-Z]{2}")) {
            throw new IOException("Expected locale in xx_XX format at i18n.preferred-locale: " + preferredLocale);
        }
        Path homeDirectory = base.resolve(string(root.getOrDefault("home-directory", ".kuudra"), "home-directory")).normalize();
        KuudraManifest.Resources manifests = loadManifests(homeDirectory.resolve("manifests"));
        return new KuudraConfig.RuntimeConfig(new KuudraConfig.RuntimeSettings(queueCapacity, workerThreads, maxEventHops,
                dispatcherPollIntervalMs, shutdownSessionDrainTimeoutMs,
                new KuudraConfig.SessionCoordinatorSettings(defaultPolicy, defaultGroupScope, maxParallelSessions, sessionQueueCapacity)),
                new KuudraConfig.ResourceSelectionSettings(namespaceMode, selectedNamespaces),
                new KuudraConfig.ReconciliationSettings(reconciliationEnabled, reconciliationIntervalMs),
                new KuudraConfig.StateStoreSettings(stateStoreBusyTimeoutMs),
                new KuudraConfig.LoggingSettings(loggingLevel, consoleEnabled, fileEnabled),
                new KuudraConfig.I18nSettings(preferredLocale), homeDirectory,
                optionalMapping(root, "global-context"), manifests);
    }

    private static java.util.Set<String> stringSet(Object value, String path) throws IOException {
        if (value == null) return java.util.Set.of();
        if (!(value instanceof List<?> values)) throw new IOException("Expected sequence at " + path);
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (Object item : values) {
            String text = string(item, path + "[]");
            if (!result.add(text)) throw new IOException("Duplicate value at " + path + ": " + text);
        }
        return java.util.Set.copyOf(result);
    }

    /** Reloads the complete authoritative manifest set from a Kuudra home manifests directory. */
    public static KuudraManifest.Resources loadManifests(Path directory) throws IOException {
        Map<KuudraManifest.ResourceId, KuudraManifest.Component> components = new LinkedHashMap<>();
        Map<KuudraManifest.ResourceId, KuudraManifest.Flow> flows = new LinkedHashMap<>();
        if (!Files.exists(directory)) return KuudraManifest.Resources.EMPTY;
        if (!Files.isDirectory(directory)) throw new IOException("Manifest directory is not a directory: " + directory);
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).filter(KuudraYamlLoader::isYaml).sorted().toList()) {
                int document = 0;
                for (ManifestDocument manifest : readAll(file)) {
                    document++;
                    if (manifest.value() == null) continue;
                    try {
                        loadManifest(manifest, file, document, components, flows);
                    } catch (IOException invalid) {
                        throw new IOException("Invalid manifest " + file + "#document-" + document + ": "
                                + invalid.getMessage(), invalid);
                    }
                }
            }
        }
        return new KuudraManifest.Resources(components, flows);
    }

    private static void loadManifest(ManifestDocument document, Path file, int documentIndex,
                                     Map<KuudraManifest.ResourceId, KuudraManifest.Component> components,
                                     Map<KuudraManifest.ResourceId, KuudraManifest.Flow> flows) throws IOException {
                String source = file + ":" + document.line("") + " (document " + documentIndex + ")";
                Map<String, Object> root = mapping(document.value(), source);
                String apiVersion = string(required(root, "apiVersion", document, "", "apiVersion: " + KuudraManifest.API_VERSION), source + ".apiVersion");
                if (!KuudraManifest.API_VERSION.equals(apiVersion)) throw new IOException("Unsupported apiVersion at " + source + ": " + apiVersion);
                String kind = string(required(root, "kind", document, "", "kind: EventSource|EventInterpreter|EventAdapter|Ingress|EventHandler|Egress|Flow"), source + ".kind");
                Map<String, Object> metadataMap = mapping(required(root, "metadata", document, "", "metadata: {namespace: default, name: resource-name}"), source + ".metadata");
                String namespace = string(metadataMap.getOrDefault("namespace", "default"), source + ".metadata.namespace");
                String name = string(required(metadataMap, "name", document, "metadata", "metadata.name: resource-name"), source + ".metadata.name");
                String resource = kind + " " + namespace + "/" + name;
                KuudraManifest.Metadata metadata;
                try {
                    metadata = new KuudraManifest.Metadata(namespace, name,
                            stringMapping(metadataMap, "labels"), stringMapping(metadataMap, "annotations"));
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid " + resource + " metadata at " + source + ": " + invalid.getMessage(), invalid);
                }
                Map<String, Object> spec = mapping(required(root, "spec", document, "", "spec: {...}"), source + ".spec");
                try {
                    if (KuudraManifest.COMPONENT_KINDS.containsKey(kind)) {
                            KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(kind, namespace, name);
                            String type = KuudraManifest.COMPONENT_KINDS.get(kind);
                            if (spec.containsKey("type")) throw new IllegalArgumentException("spec.type has been removed; use kind: " + kind);
                            KuudraManifest.Component component = new KuudraManifest.Component(id, metadata,
                                    type, string(required(spec, "component", document, "spec", "spec.component: plugin-namespace/component-name"), source + ".spec.component"),
                                    string(spec.getOrDefault("desiredState", defaultComponentState(type)), source + ".spec.desiredState").toLowerCase(java.util.Locale.ROOT),
                                    optionalMapping(spec, "options"));
                            if (components.putIfAbsent(id, component) != null) throw new IOException("Duplicate resource identity: " + id);
                    } else switch (kind) {
                        case "Flow" -> {
                            if (spec.containsKey("desiredState")) throw new IllegalArgumentException("Flow is a routing declaration and does not support spec.desiredState");
                            KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(kind, namespace, name);
                            Map<String, KuudraManifest.ResourceReference> imports = new LinkedHashMap<>();
                            for (Map.Entry<String, Object> entry : mapping(required(spec, "imports", document, "spec", "spec.imports: {alias: {kind: EventSource, name: resource-name}}"), source + ".spec.imports").entrySet()) {
                                Map<String, Object> reference = mapping(entry.getValue(), source + ".spec.imports." + entry.getKey());
                                imports.put(entry.getKey(), new KuudraManifest.ResourceReference(
                                        string(required(reference, "kind", document, "spec.imports." + entry.getKey(), "kind: EventSource"), "reference.kind"),
                                        string(reference.getOrDefault("namespace", namespace), "reference.namespace"),
                                        string(required(reference, "name", document, "spec.imports." + entry.getKey(), "name: resource-name"), "reference.name")));
                            }
                            List<KuudraConfig.EdgeConfig> edges = new ArrayList<>();
                            for (Object item : list(requiredForResource(spec, "edges", document, "spec",
                                    "spec.edges: [{from: source, to: ingress}]", resource, source))) {
                                Map<String, Object> edge = mapping(item, source + ".spec.edges");
                                edges.add(new KuudraConfig.EdgeConfig(string(required(edge, "from"), "edge.from"), string(required(edge, "to"), "edge.to")));
                            }
                            KuudraManifest.Flow flow = new KuudraManifest.Flow(id, metadata, imports, edges);
                            if (flows.putIfAbsent(id, flow) != null) throw new IOException("Duplicate resource identity: " + id);
                        }
                        default -> throw new IOException("Unsupported manifest kind at " + source + ": " + kind);
                    }
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid " + resource + " at " + source + ": " + invalid.getMessage(), invalid);
                }
    }

    private static String defaultComponentState(Object type) {
        return java.util.Set.of("event-source", "event-interpreter", "event-handler").contains(type)
                ? "running" : "active";
    }
    private static boolean isYaml(Path path) { String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT); return name.endsWith(".yaml") || name.endsWith(".yml"); }
    private static Map<String, String> stringMapping(Map<String, Object> map, String key) throws IOException {
        if (!map.containsKey(key)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapping(map.get(key), key).entrySet()) result.put(entry.getKey(), string(entry.getValue(), key + "." + entry.getKey()));
        return Map.copyOf(result);
    }

    private static Object read(Path file) throws IOException { try (Reader reader = Files.newBufferedReader(file)) { return new Yaml().load(reader); } }
    private static List<ManifestDocument> readAll(Path file) throws IOException {
        List<Object> values = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            new Yaml().loadAll(reader).forEach(values::add);
        } catch (MarkedYAMLException invalid) {
            int line = invalid.getProblemMark() == null ? 1 : invalid.getProblemMark().getLine() + 1;
            int column = invalid.getProblemMark() == null ? 1 : invalid.getProblemMark().getColumn() + 1;
            throw new IOException("Invalid YAML syntax at " + file + ":" + line + ":" + column + ": "
                    + invalid.getProblem(), invalid);
        } catch (YAMLException invalid) {
            throw new IOException("Invalid YAML syntax in " + file + ": " + invalid.getMessage(), invalid);
        }
        List<Node> nodes = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) { new Yaml().composeAll(reader).forEach(nodes::add); }
        List<ManifestDocument> documents = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Map<String, Integer> lines = new LinkedHashMap<>();
            Node node = index < nodes.size() ? nodes.get(index) : null;
            if (node != null) collectLines(node, "", lines);
            documents.add(new ManifestDocument(values.get(index), lines));
        }
        return List.copyOf(documents);
    }

    private static void collectLines(Node node, String path, Map<String, Integer> lines) {
        lines.putIfAbsent(path, node.getStartMark().getLine() + 1);
        if (node instanceof MappingNode mapping) {
            mapping.getValue().forEach(tuple -> {
                if (tuple.getKeyNode() instanceof ScalarNode key) {
                    String child = path.isEmpty() ? key.getValue() : path + "." + key.getValue();
                    collectLines(tuple.getValueNode(), child, lines);
                }
            });
        } else if (node instanceof SequenceNode sequence) {
            for (int index = 0; index < sequence.getValue().size(); index++) {
                collectLines(sequence.getValue().get(index), path + "[" + index + "]", lines);
            }
        }
    }
    private static void mergeMappings(Map<String, Object> target, Map<String, Object> source) throws IOException {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object existing = target.get(entry.getKey());
            if (existing instanceof Map<?, ?> && entry.getValue() instanceof Map<?, ?>) {
                Map<String, Object> nested = new LinkedHashMap<>(mapping(existing, entry.getKey()));
                mergeMappings(nested, mapping(entry.getValue(), entry.getKey()));
                target.put(entry.getKey(), nested);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }
    private static Map<String, Object> mapping(Object value, Object location) throws IOException {
        if (!(value instanceof Map<?, ?> source)) throw new IOException("Expected mapping at " + location);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) { if (!(entry.getKey() instanceof String key)) throw new IOException("Mapping key must be text at " + location); result.put(key, entry.getValue()); }
        return result;
    }
    private static Map<String, Object> optionalMapping(Map<String, Object> map, String key) throws IOException { return !map.containsKey(key) ? Map.of() : mapping(map.get(key), key); }
    private static List<Object> list(Object value) throws IOException { if (value == null) return List.of(); if (!(value instanceof List<?> list)) throw new IOException("Expected list"); return List.copyOf(list); }
    private static Object required(Map<String, Object> map, String key) throws IOException { Object value = map.get(key); if (value == null) throw new IOException("Missing required value: " + key); return value; }
    private static Object required(Map<String, Object> map, String key, ManifestDocument document,
                                   String parentPath, String example) throws IOException {
        Object value = map.get(key);
        if (value != null) return value;
        String path = parentPath.isEmpty() ? key : parentPath + "." + key;
        throw new IOException("Missing required field '" + path + "' near line " + document.line(parentPath)
                + "; expected format: " + example);
    }
    private static Object requiredForResource(Map<String, Object> map, String key, ManifestDocument document,
                                              String parentPath, String example, String resource, String source) throws IOException {
        try {
            return required(map, key, document, parentPath, example);
        } catch (IOException invalid) {
            throw new IOException("Invalid " + resource + " at " + source + ": " + invalid.getMessage(), invalid);
        }
    }
    private static String string(Object value, String location) throws IOException { if (!(value instanceof String text) || text.isBlank()) throw new IOException("Expected non-blank string at " + location); return text; }
    private static int integer(Map<String, Object> map, String key, int fallback) throws IOException { Object value = map.get(key); if (value == null) return fallback; if (value instanceof Number number) return number.intValue(); try { return Integer.parseInt(string(value, key)); } catch (NumberFormatException error) { throw new IOException("Expected integer at " + key, error); } }
    private static int nonNegativeInteger(Map<String, Object> map, String key, int fallback) throws IOException {
        int value = integer(map, key, fallback);
        if (value < 0) throw new IOException("Expected non-negative integer at " + key);
        return value;
    }
    private static <E extends Enum<E>> E enumValue(Map<String, Object> map, String key, E fallback, Class<E> type) throws IOException {
        Object value = map.get(key); if (value == null) return fallback;
        try { return Enum.valueOf(type, string(value, key).replace('-', '_').toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IOException("Unsupported value at " + key + ": " + value, error); }
    }
    private static boolean bool(Object value, boolean fallback) throws IOException { if (value == null) return fallback; if (value instanceof Boolean flag) return flag; if (value instanceof String text) return Boolean.parseBoolean(text); throw new IOException("Expected boolean"); }
    private record ManifestDocument(Object value, Map<String, Integer> lines) {
        int line(String path) { return lines.getOrDefault(path, lines.getOrDefault("", 1)); }
    }
}
