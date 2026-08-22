package io.github.actforever.kuudra.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, namespaced data exchanged between components from different plugins.
 *
 * <p>Components must write under an owned namespace (normally their plugin id),
 * so two plugins cannot silently overwrite each other's values. Raw legacy maps
 * are imported into the reserved {@value #CORE_NAMESPACE} namespace.</p>
 */
public final class SignalData {
    public static final String CORE_NAMESPACE = "core";
    private final Map<String, Map<String, Object>> namespaces;

    private SignalData(Map<String, Map<String, Object>> namespaces) {
        this.namespaces = immutableNamespaces(namespaces);
    }

    public static SignalData empty() { return new SignalData(Map.of()); }

    public static SignalData fromLegacy(Map<String, Object> values) {
        return new SignalData(Map.of(CORE_NAMESPACE, values));
    }

    public static SignalData of(String namespace, Map<String, Object> values) {
        return new SignalData(Map.of(namespace, values));
    }

    public Optional<Object> find(String namespace, String key) {
        return Optional.ofNullable(namespace(namespace).get(requireKey(key)));
    }

    public <T> Optional<T> find(String namespace, String key, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return find(namespace, key).filter(type::isInstance).map(type::cast);
    }

    public Object require(String namespace, String key) {
        return find(namespace, key).orElseThrow(() -> new IllegalArgumentException("Signal data is missing " + namespace + "." + key));
    }

    public SignalData with(String namespace, String key, Object value) {
        String checkedNamespace = requireNamespace(namespace);
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>(namespaces);
        Map<String, Object> section = new LinkedHashMap<>(copy.getOrDefault(checkedNamespace, Map.of()));
        section.put(requireKey(key), freeze(value));
        copy.put(checkedNamespace, section);
        return new SignalData(copy);
    }

    public Map<String, Object> namespace(String namespace) {
        return namespaces.getOrDefault(requireNamespace(namespace), Map.of());
    }

    public Map<String, Map<String, Object>> namespaces() { return namespaces; }

    @Override public boolean equals(Object other) {
        return other instanceof SignalData data && namespaces.equals(data.namespaces);
    }
    @Override public int hashCode() { return namespaces.hashCode(); }
    @Override public String toString() { return namespaces.toString(); }

    private static Map<String, Map<String, Object>> immutableNamespaces(Map<String, Map<String, Object>> source) {
        Objects.requireNonNull(source, "namespaces");
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        source.forEach((namespace, values) -> {
            Map<String, Object> section = new LinkedHashMap<>();
            Objects.requireNonNull(values, "values").forEach((key, value) -> section.put(requireKey(key), freeze(value)));
            result.put(requireNamespace(namespace), Map.copyOf(section));
        });
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Object freeze(Object value) {
        Objects.requireNonNull(value, "SignalData does not permit null values");
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof SignalData data) return data;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("Signal data map keys must be strings");
                copy.put(text, freeze(nested));
            });
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(element -> copy.add(freeze(element)));
            return List.copyOf(copy);
        }
        throw new IllegalArgumentException("Unsupported SignalData value: " + value.getClass().getName());
    }

    private static String requireNamespace(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("namespace must not be blank");
        return value;
    }
    private static String requireKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("key must not be blank");
        return value;
    }
}
