package io.github.actforever.kuudra.config;

import io.github.actforever.kuudra.api.SessionPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * A deliberately small YAML subset compiler used by the first executable demo.
 * It accepts nested mappings with scalar values only; lists, anchors, tags and flow-style YAML
 * are intentionally rejected until a complete YAML adapter is introduced.
 */
public final class KuudraConfig {
    private KuudraConfig() { }

    public static DemoConfig loadDemo(Reader input) throws IOException {
        Map<String, String> values = new HashMap<>();
        String section = null;
        try (BufferedReader reader = new BufferedReader(input)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String withoutComment = line.replaceFirst("\\s+#.*$", "");
                if (withoutComment.isBlank()) continue;
                int indent = withoutComment.indexOf(withoutComment.stripLeading());
                String content = withoutComment.trim();
                if (content.startsWith("-") || content.contains("[") || content.contains("{") || !content.contains(":")) {
                    throw new IOException("unsupported demo YAML at line " + lineNumber + ": " + line);
                }
                String[] entry = content.split(":", 2);
                String key = entry[0].trim();
                String value = unquote(entry[1].trim());
                if (value.isEmpty()) {
                    if (indent != 0) throw new IOException("nested section is not supported at line " + lineNumber);
                    section = key;
                } else {
                    if (section == null || indent == 0) throw new IOException("expected a section before line " + lineNumber);
                    values.put(section + "." + key, value);
                }
            }
        }
        return new DemoConfig(
                integer(values, "runtime.queueCapacity"),
                integer(values, "runtime.actorThreads"),
                required(values, "flow.id"),
                required(values, "flow.sessionName"),
                SessionPolicy.valueOf(required(values, "flow.policy")),
                required(values, "flow.acceptType"),
                required(values, "action.simulateKey"),
                required(values, "ingress.id"),
                required(values, "ingress.inputType"),
                required(values, "ingress.key"),
                integer(values, "ingress.doublePressWindowMs")
        );
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IOException("missing required value: " + key);
        return value;
    }

    private static int integer(Map<String, String> values, String key) throws IOException {
        try { return Integer.parseInt(required(values, key)); }
        catch (NumberFormatException e) { throw new IOException("expected integer for " + key, e); }
    }

    private static String unquote(String value) {
        return value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
                ? value.substring(1, value.length() - 1) : value;
    }

    public record DemoConfig(int queueCapacity, int actorThreads, String flowId, String sessionName,
                             SessionPolicy policy, String acceptType, String simulateKey,
                             String ingressId, String inputType, String key, int doublePressWindowMs) { }
}
