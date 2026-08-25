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

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Default codec backed by Jackson; stored values contain no plugin object references. */
public final class JsonContextCodec implements ContextCodec {
    private final ObjectMapper mapper;

    public JsonContextCodec() { this(new ObjectMapper().findAndRegisterModules()); }
    public JsonContextCodec(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper, "mapper"); }

    @Override public Object encode(Object value) {
        Objects.requireNonNull(value, "value");
        return freeze(mapper.convertValue(value, Object.class));
    }

    @Override public <T> T decode(Object value, Type targetType) {
        Objects.requireNonNull(value, "value");
        JavaType type = mapper.getTypeFactory().constructType(Objects.requireNonNull(targetType, "targetType"));
        return mapper.convertValue(value, type);
    }

    @Override public Object parseLiteral(String value) {
        try {
            return freeze(mapper.readValue(Objects.requireNonNull(value, "value"), Object.class));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Invalid JSON literal", error);
        }
    }

    private static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("JSON object keys must be strings");
                result.put(text, freeze(item));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> source) {
            List<Object> result = new ArrayList<>(source.size());
            source.forEach(item -> result.add(freeze(item)));
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("Value cannot be represented as JSON: " + value.getClass().getName());
    }
}
