package io.github.actforever.kuudra.plugin;

import java.util.List;

/** Structured documentation for one component-specific spec.options property. */
public record PluginConfigurationDocumentation(String path, String type, boolean required,
                                               String defaultValue, String description,
                                               List<Object> examples, List<String> allowedValues) {
    public PluginConfigurationDocumentation {
        path = path == null ? "" : path;
        type = type == null ? "" : type;
        defaultValue = defaultValue == null ? "" : defaultValue;
        description = description == null ? "" : description;
        examples = List.copyOf(examples);
        allowedValues = List.copyOf(allowedValues);
        if (path.isBlank() || type.isBlank() || description.isBlank()) {
            throw new IllegalArgumentException("configuration path, type, and description must not be blank");
        }
        if (allowedValues.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("configuration allowedValues must not contain blanks: " + path);
        }
    }
}
