package io.github.actforever.kuudra.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, namespaced attributes exchanged by Event components. */
public final class EventData {
    public static final String CORE_NAMESPACE = "core";
    private final Map<String, Map<String, Object>> namespaces;

    private EventData(Map<String, Map<String, Object>> namespaces) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        Objects.requireNonNull(namespaces, "namespaces").forEach((namespace, values) -> {
            requireText(namespace, "namespace");
            Map<String, Object> section = new LinkedHashMap<>();
            Objects.requireNonNull(values, "values").forEach((key, value) -> {
                requireText(key, "attribute key"); section.put(key, freeze(value));
            });
            copy.put(namespace, Map.copyOf(section));
        });
        this.namespaces = Map.copyOf(copy);
    }

    public static EventData empty() { return new EventData(Map.of()); }
    public static EventData fromLegacy(Map<String, Object> values) { return new EventData(Map.of(CORE_NAMESPACE, values)); }
    public static EventData of(String namespace, Map<String, Object> values) { return new EventData(Map.of(namespace, values)); }
    public Optional<Object> find(String namespace, String key) { return Optional.ofNullable(namespace(namespace).get(requireText(key, "attribute key"))); }
    public Object require(String namespace, String key) { return find(namespace, key).orElseThrow(() -> new IllegalArgumentException("Event data is missing " + namespace + "." + key)); }
    public EventData with(String namespace, String key, Object value) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>(namespaces);
        Map<String, Object> section = new LinkedHashMap<>(copy.getOrDefault(namespace, Map.of()));
        section.put(requireText(key, "attribute key"), freeze(value)); copy.put(requireText(namespace, "namespace"), section); return new EventData(copy);
    }
    public Map<String, Object> namespace(String namespace) { return namespaces.getOrDefault(requireText(namespace, "namespace"), Map.of()); }
    public Map<String, Map<String, Object>> namespaces() { return namespaces; }
    @Override public boolean equals(Object other) { return other instanceof EventData data && namespaces.equals(data.namespaces); }
    @Override public int hashCode() { return namespaces.hashCode(); }
    @Override public String toString() { return namespaces.toString(); }
    @SuppressWarnings("unchecked")
    private static Object freeze(Object value) {
        if (value == null) throw new IllegalArgumentException("EventData does not permit null values");
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof EventData) return value;
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> copy = new LinkedHashMap<>();
            values.forEach((key, item) -> { if (!(key instanceof String text)) throw new IllegalArgumentException("EventData map keys must be strings"); copy.put(text, freeze(item)); });
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> values) { List<Object> copy = new ArrayList<>(); values.forEach(item -> copy.add(freeze(item))); return List.copyOf(copy); }
        throw new IllegalArgumentException("Unsupported EventData value: " + value.getClass().getName());
    }
    private static String requireText(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank"); return value; }
}
