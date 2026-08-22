package io.github.actforever.kuudra.api;

import java.lang.reflect.Type;

/** Converts plugin values to a class-loader-neutral immutable value tree and back. */
public interface ContextCodec {
    Object encode(Object value);
    <T> T decode(Object value, Type targetType);
    /** Parses a serialized literal; codecs that do not support text formats may preserve the input string. */
    default Object parseLiteral(String value) { return value; }
}
