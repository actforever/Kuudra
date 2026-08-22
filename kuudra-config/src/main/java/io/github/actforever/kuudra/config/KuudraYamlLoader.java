package io.github.actforever.kuudra.config;

import org.yaml.snakeyaml.Yaml;

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
        if (root.containsKey("flows-directory")) throw new IOException("Configuration key 'flows-directory' has been removed; use <home-directory>/flows");
        Map<String, Object> runtime = optionalMapping(root, "runtime");
        int queueCapacity = integer(runtime, "queue-capacity", 1_024);
        int workerThreads = integer(runtime, "worker-threads", Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        Path homeDirectory = base.resolve(string(root.getOrDefault("home-directory", ".kuudra"), "home-directory")).normalize();
        Map<String, KuudraConfig.FlowConfig> flows = loadFlows(homeDirectory.resolve("flows"));
        return new KuudraConfig.RuntimeConfig(new KuudraConfig.RuntimeSettings(queueCapacity, workerThreads), homeDirectory,
                optionalMapping(root, "global-context"), flows);
    }

    private static Map<String, KuudraConfig.FlowConfig> loadFlows(Path directory) throws IOException {
        if (!Files.exists(directory)) return Map.of();
        if (!Files.isDirectory(directory)) throw new IOException("Flow directory is not a directory: " + directory);
        Map<String, KuudraConfig.FlowConfig> result = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yaml") || path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                KuudraConfig.FlowConfig flow = flow(mapping(read(file), file));
                if (result.putIfAbsent(flow.id(), flow) != null) throw new IOException("Duplicate Flow id: " + flow.id());
            }
        }
        return Map.copyOf(result);
    }
    private static KuudraConfig.FlowConfig flow(Map<String, Object> map) throws IOException {
        String id = string(required(map, "id"), "id");
        Map<String, KuudraConfig.NodeConfig> nodes = new LinkedHashMap<>();
        Map<String, Object> componentDefinitions = map.containsKey("components") ? optionalMapping(map, "components") : optionalMapping(map, "nodes");
        for (Map.Entry<String, Object> entry : componentDefinitions.entrySet()) {
            Map<String, Object> node = mapping(entry.getValue(), "node " + entry.getKey());
            if ("event-source".equals(node.get("type"))) continue;
            nodes.put(entry.getKey(), new KuudraConfig.NodeConfig(entry.getKey(), string(required(node, "type"), "node.type"),
                    node.containsKey("component") ? string(node.get("component"), "node.component") : null, optionalMapping(node, "options")));
        }
        List<KuudraConfig.EdgeConfig> edges = new ArrayList<>();
        for (Object item : list(map.containsKey("routes") ? map.get("routes") : map.get("edges"))) { Map<String, Object> edge = mapping(item, "edge"); edges.add(new KuudraConfig.EdgeConfig(string(required(edge, "from"), "edge.from"), string(required(edge, "to"), "edge.to"))); }
        List<KuudraConfig.SourceBinding> sources = new ArrayList<>();
        if (map.containsKey("components")) {
            for (Map.Entry<String, Object> entry : componentDefinitions.entrySet()) {
                Map<String, Object> component = mapping(entry.getValue(), "component " + entry.getKey());
                if ("event-source".equals(component.get("type"))) {
                    sources.add(new KuudraConfig.SourceBinding(entry.getKey(), string(required(component, "component"), "component.component"),
                            string(required(component, "target"), "component.target"), bool(component.get("enabled"), true)));
                }
            }
        } else {
            for (Object item : list(map.get("sources"))) { Map<String, Object> source = mapping(item, "source"); String component = string(required(source, "component"), "source.component"); sources.add(new KuudraConfig.SourceBinding(string(source.getOrDefault("id", component), "source.id"), component, string(required(source, "target-node-id"), "source.target-node-id"), bool(source.get("enabled"), true))); }
        }
        return new KuudraConfig.FlowConfig(id, nodes, edges, sources);
    }
    private static Object read(Path file) throws IOException { try (Reader reader = Files.newBufferedReader(file)) { return new Yaml().load(reader); } }
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
    private static String string(Object value, String location) throws IOException { if (!(value instanceof String text) || text.isBlank()) throw new IOException("Expected non-blank string at " + location); return text; }
    private static int integer(Map<String, Object> map, String key, int fallback) throws IOException { Object value = map.get(key); if (value == null) return fallback; if (value instanceof Number number) return number.intValue(); try { return Integer.parseInt(string(value, key)); } catch (NumberFormatException error) { throw new IOException("Expected integer at " + key, error); } }
    private static boolean bool(Object value, boolean fallback) throws IOException { if (value == null) return fallback; if (value instanceof Boolean flag) return flag; if (value instanceof String text) return Boolean.parseBoolean(text); throw new IOException("Expected boolean"); }
}
