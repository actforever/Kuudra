package io.github.actforever.kuudra.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral, already-resolved configuration resource.
 *
 * <p>The mapping may originate from a YAML file, a framework configuration
 * environment, or a programmatic host. Relative plugin and Flow locations are
 * resolved from {@link #baseDirectory()}.</p>
 */
public record KuudraConfigResource(Map<String, Object> values, Path baseDirectory, String description) {
    public KuudraConfigResource {
        values = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
        baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory").toAbsolutePath().normalize();
        description = description == null || description.isBlank() ? "configuration resource" : description;
    }
}
