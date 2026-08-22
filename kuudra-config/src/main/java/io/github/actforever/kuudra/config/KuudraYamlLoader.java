package io.github.actforever.kuudra.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Reads kuudra.yaml plus Flow YAML files into the format-neutral configuration model. */
public final class KuudraYamlLoader {
    private KuudraYamlLoader() { }

    public static KuudraConfig.RuntimeConfig load(Path file) throws IOException {
        Path configFile = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (!Files.isRegularFile(configFile)) throw new IOException("Kuudra configuration does not exist: " + configFile);
        Path base = configFile.getParent();
        Map<String, Object> root = mapping(read(configFile), configFile);
        Map<String, Object> runtime = optionalMapping(root, "runtime");
        int queueCapacity = integer(runtime, "queueCapacity", 1_024);
        int workerThreads = integer(runtime, "workerThreads", Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        Map<String, Object> plugins = optionalMapping(root, "plugins");
        List<Path> pluginDirectories = strings(plugins.get("directories")).stream().map(value -> base.resolve(value).normalize()).toList();
        String flowsDirectory = string(root.getOrDefault("flowsDirectory", "flows"), "flowsDirectory");
        Map<String, KuudraConfig.FlowConfig> flows = loadFlows(base.resolve(flowsDirectory).normalize());
        return new KuudraConfig.RuntimeConfig(new KuudraConfig.RuntimeSettings(queueCapacity, workerThreads), pluginDirectories,
                optionalMapping(root, "globalContext"), flows);
    }

    private static Map<String, KuudraConfig.FlowConfig> loadFlows(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("Flow directory does not exist: " + directory);
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
        for (Map.Entry<String, Object> entry : optionalMapping(map, "nodes").entrySet()) {
            Map<String, Object> node = mapping(entry.getValue(), "node " + entry.getKey());
            nodes.put(entry.getKey(), new KuudraConfig.NodeConfig(entry.getKey(), string(required(node, "type"), "node.type"),
                    node.containsKey("component") ? string(node.get("component"), "node.component") : null, optionalMapping(node, "options")));
        }
        List<KuudraConfig.EdgeConfig> edges = new ArrayList<>();
        for (Object item : list(map.get("edges"))) { Map<String, Object> edge = mapping(item, "edge"); edges.add(new KuudraConfig.EdgeConfig(string(required(edge, "from"), "edge.from"), string(required(edge, "to"), "edge.to"))); }
        List<KuudraConfig.SourceBinding> sources = new ArrayList<>();
        for (Object item : list(map.get("sources"))) { Map<String, Object> source = mapping(item, "source"); sources.add(new KuudraConfig.SourceBinding(string(required(source, "component"), "source.component"), string(required(source, "targetNodeId"), "source.targetNodeId"))); }
        return new KuudraConfig.FlowConfig(id, nodes, edges, sources);
    }
    private static Object read(Path file) throws IOException { try (Reader reader = Files.newBufferedReader(file)) { return new Yaml().load(reader); } }
    private static Map<String, Object> mapping(Object value, Object location) throws IOException {
        if (!(value instanceof Map<?, ?> source)) throw new IOException("Expected mapping at " + location);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) { if (!(entry.getKey() instanceof String key)) throw new IOException("Mapping key must be text at " + location); result.put(key, entry.getValue()); }
        return result;
    }
    private static Map<String, Object> optionalMapping(Map<String, Object> map, String key) throws IOException { return !map.containsKey(key) ? Map.of() : mapping(map.get(key), key); }
    private static List<Object> list(Object value) throws IOException { if (value == null) return List.of(); if (!(value instanceof List<?> list)) throw new IOException("Expected list"); return List.copyOf(list); }
    private static List<String> strings(Object value) throws IOException { List<String> result = new ArrayList<>(); for (Object item : list(value)) result.add(string(item, "list item")); return List.copyOf(result); }
    private static Object required(Map<String, Object> map, String key) throws IOException { Object value = map.get(key); if (value == null) throw new IOException("Missing required value: " + key); return value; }
    private static String string(Object value, String location) throws IOException { if (!(value instanceof String text) || text.isBlank()) throw new IOException("Expected non-blank string at " + location); return text; }
    private static int integer(Map<String, Object> map, String key, int fallback) throws IOException { Object value = map.get(key); if (value == null) return fallback; if (value instanceof Number number) return number.intValue(); try { return Integer.parseInt(string(value, key)); } catch (NumberFormatException error) { throw new IOException("Expected integer at " + key, error); } }
}
