package io.github.actforever.kuudra.api;

import java.util.Map;
import java.util.function.UnaryOperator;

/** Mutable only through atomic whole-map updates; every reader receives an immutable snapshot. */
public interface SessionContext {
    Map<String, Object> snapshot();
    boolean compareAndSet(Map<String, Object> expected, Map<String, Object> replacement);
    Map<String, Object> update(UnaryOperator<Map<String, Object>> operation);
}
