package io.github.actforever.kuudra.i18n;

import java.util.Map;
import java.util.Optional;

/** Resolves a stable message key and structured template arguments into localized display text. */
@FunctionalInterface
public interface MessageResolver {
    Optional<String> resolve(String messageKey, Map<String, Object> arguments);

    static MessageResolver none() { return (key, arguments) -> Optional.empty(); }
}
