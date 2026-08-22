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
        List<String> dependencies = List.of();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line; int number = 0;
            while ((line = reader.readLine()) != null) {
                number++; String content = line.replaceFirst("\\s+#.*$", "").trim();
                if (content.isBlank()) continue;
                if (content.startsWith("[") || !content.contains("=")) throw new IOException("Unsupported metadata TOML at line " + number);
                String[] parts = content.split("=", 2); String key = parts[0].trim(); String value = parts[1].trim();
                if (key.equals("dependencies")) dependencies = stringArray(value, number);
                else if (key.equals("id") || key.equals("namespace") || key.equals("version") || key.equals("entrypoint")) scalar.put(key, string(value, number));
                else throw new IOException("Unknown metadata key at line " + number + ": " + key);
            }
        }
        try { return new PluginMetadata(required(scalar, "id"), required(scalar, "namespace"), required(scalar, "version"), required(scalar, "entrypoint"), dependencies); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid plugin metadata", error); }
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key); if (value == null) throw new IOException("Missing metadata key: " + key); return value;
    }
    private static String string(String value, int line) throws IOException {
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) throw new IOException("Expected quoted string at line " + line);
        return value.substring(1, value.length() - 1);
    }
    private static List<String> stringArray(String value, int line) throws IOException {
        if (!value.startsWith("[") || !value.endsWith("]")) throw new IOException("Expected string array at line " + line);
        String content = value.substring(1, value.length() - 1).trim();
        if (content.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String item : content.split(",")) result.add(string(item.trim(), line));
        return List.copyOf(result);
    }
}
