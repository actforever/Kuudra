package io.github.actforever.kuudra.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Strict, deliberately small TOML reader for the stable plugin metadata contract. */
final class PluginMetadataToml {
    static final String PATH = "META-INF/kuudra-plugin/metadata.toml";
    private PluginMetadataToml() { }

    static PluginMetadata read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Missing " + PATH);
        Map<String, String> scalar = new HashMap<>();
        List<Map<String, String>> dependencyValues = new ArrayList<>();
        Map<String, String> currentDependency = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line; int number = 0;
            while ((line = reader.readLine()) != null) {
                number++; String content = line.replaceFirst("(^|\\s+)#.*$", "").trim();
                if (content.isBlank()) continue;
                if (content.equals("[[dependencies]]")) {
                    currentDependency = new HashMap<>(); dependencyValues.add(currentDependency); continue;
                }
                if (content.startsWith("[") || !content.contains("=")) throw new IOException("Unsupported metadata TOML at line " + number);
                String[] parts = content.split("=", 2); String key = parts[0].trim(); String value = parts[1].trim();
                if (currentDependency != null) {
                    if (key.equals("namespace") || key.equals("pluginId") || key.equals("versionRange")) currentDependency.put(key, string(value, number));
                    else if (key.equals("mandatory")) currentDependency.put(key, bool(value, number));
                    else throw new IOException("Unknown dependency metadata key at line " + number + ": " + key);
                } else if (key.equals("id") || key.equals("namespace") || key.equals("version") || key.equals("entrypoint")) scalar.put(key, string(value, number));
                else throw new IOException("Unknown metadata key at line " + number + ": " + key);
            }
        }
        try {
            List<PluginDependency> dependencies = dependencyValues.stream().map(values -> new PluginDependency(
                    uncheckedRequired(values, "namespace"), uncheckedRequired(values, "pluginId"),
                    Boolean.parseBoolean(uncheckedRequired(values, "mandatory")), uncheckedRequired(values, "versionRange"))).toList();
            return new PluginMetadata(required(scalar, "id"), required(scalar, "namespace"), required(scalar, "version"), required(scalar, "entrypoint"), dependencies);
        }
        catch (IllegalArgumentException error) { throw new IOException("Invalid plugin metadata", error); }
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key); if (value == null) throw new IOException("Missing metadata key: " + key); return value;
    }
    private static String string(String value, int line) throws IOException {
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) throw new IOException("Expected quoted string at line " + line);
        return value.substring(1, value.length() - 1);
    }
    private static String bool(String value, int line) throws IOException {
        if (!value.equals("true") && !value.equals("false")) throw new IOException("Expected boolean at line " + line);
        return value;
    }
    private static String uncheckedRequired(Map<String, String> values, String key) {
        String value = values.get(key); if (value == null) throw new IllegalArgumentException("Missing dependency metadata key: " + key); return value;
    }
}
