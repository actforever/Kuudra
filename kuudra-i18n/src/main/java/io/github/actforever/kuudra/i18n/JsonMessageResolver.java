package io.github.actforever.kuudra.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable JSON catalog using {name} template placeholders. */
public final class JsonMessageResolver implements MessageResolver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, String> templates;

    private JsonMessageResolver(Map<String, String> templates) { this.templates = Map.copyOf(templates); }

    public static JsonMessageResolver read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        return new JsonMessageResolver(JSON.readValue(input, new TypeReference<LinkedHashMap<String, String>>() { }));
    }

    @Override public Optional<String> resolve(String messageKey, Map<String, Object> arguments) {
        String template = templates.getOrDefault(messageKey, templates.get("*"));
        if (template == null) return Optional.empty();
        Map<String, Object> values = new LinkedHashMap<>(arguments);
        values.putIfAbsent("messageKey", messageKey);
        values.putIfAbsent("arguments", arguments);
        String resolved = template;
        for (Map.Entry<String, Object> entry : values.entrySet())
            resolved = resolved.replace("{" + entry.getKey() + "}", Objects.toString(entry.getValue()));
        return Optional.of(resolved);
    }
}
