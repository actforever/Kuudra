package io.github.actforever.kuudra.api.context;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable read-only view that centralizes map lookup and codec-backed type conversion. */
public final class TypedValueMap {
    private final Map<String, Object> values;
    private final ContextCodec codec;

    private TypedValueMap(Map<String, Object> values, ContextCodec codec) {
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public static TypedValueMap of(Map<String, Object> values) {
        return new TypedValueMap(values, ContextCodecs.defaultCodec());
    }

    public static TypedValueMap of(Map<String, Object> values, ContextCodec codec) {
        return new TypedValueMap(values, codec);
    }

    public static <T> T get(Map<String, Object> values, String key, Class<T> type) {
        return get(values, key, (Type) type, ContextCodecs.defaultCodec());
    }

    public static <T> T get(Map<String, Object> values, String key, Type type, ContextCodec codec) {
        Object value = Objects.requireNonNull(values, "values").get(requireKey(key));
        if (value == null) throw new IllegalArgumentException("Value is missing: " + key);
        return Objects.requireNonNull(codec, "codec").decode(value, type);
    }

    public static <T> T getOrDefault(Map<String, Object> values, String key, Class<T> type, T fallback) {
        return getOrDefault(values, key, (Type) type, fallback, ContextCodecs.defaultCodec());
    }

    public static <T> T getOrDefault(Map<String, Object> values, String key, Type type, T fallback, ContextCodec codec) {
        Object value = Objects.requireNonNull(values, "values").get(requireKey(key));
        return value == null ? fallback : Objects.requireNonNull(codec, "codec").decode(value, type);
    }

    public Map<String, Object> asMap() { return values; }
    public boolean contains(String key) { return values.containsKey(requireKey(key)); }
    public Optional<Object> find(String key) { return Optional.ofNullable(values.get(requireKey(key))); }
    public Object get(String key) {
        return find(key).orElseThrow(() -> new IllegalArgumentException("Value is missing: " + key));
    }
    public <T> T get(String key, Class<T> type) { return get(key, (Type) type); }
    public <T> T get(String key, Type type) { return get(values, key, type, codec); }
    public <T> T getOrDefault(String key, Class<T> type, T fallback) {
        return getOrDefault(key, (Type) type, fallback);
    }
    public <T> T getOrDefault(String key, Type type, T fallback) {
        return getOrDefault(values, key, type, fallback, codec);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("value key must not be blank");
        return key;
    }
}
