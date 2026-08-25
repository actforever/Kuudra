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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Concurrent mutable scope whose stored values use a codec-neutral immutable representation. */
public interface ValueContext {
    Map<String, Object> snapshot();
    boolean compareAndSet(Map<String, Object> expected, Map<String, Object> replacement);
    Map<String, Object> update(UnaryOperator<Map<String, Object>> operation);
    default ContextCodec codec() { return ContextCodecs.defaultCodec(); }

    default Optional<Object> find(String key) { return Optional.ofNullable(snapshot().get(requireKey(key))); }
    default Object get(String key) { return find(key).orElseThrow(() -> new IllegalArgumentException("Context value is missing: " + key)); }
    default <T> T get(String key, Class<T> type) { return get(key, (Type) type); }
    default <T> T get(String key, Type type) { return codec().decode(get(key), type); }
    default Map<String, Object> put(String key, Object value) {
        String checked = requireKey(key);
        Object encoded = codec().encode(value);
        return update(current -> { Map<String, Object> next = new LinkedHashMap<>(current); next.put(checked, encoded); return next; });
    }
    default Map<String, Object> remove(String key) {
        String checked = requireKey(key);
        return update(current -> { Map<String, Object> next = new LinkedHashMap<>(current); next.remove(checked); return next; });
    }
    private static String requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("context key must not be blank");
        return key;
    }
}
