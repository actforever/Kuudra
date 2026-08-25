package io.github.actforever.kuudra.plugin;

import java.util.Map;

/** Framework logger bound to one plugin identity and projected through SystemEvent. */
public interface PluginLogger {
    String MESSAGE_KEY_FIELD = "kuudra.i18n.message-key";
    void log(PluginLogLevel level, String message, Map<String, Object> fields, Throwable error);

    default void trace(String message) { log(PluginLogLevel.TRACE, message, Map.of(), null); }
    default void debug(String message) { log(PluginLogLevel.DEBUG, message, Map.of(), null); }
    default void info(String message) { log(PluginLogLevel.INFO, message, Map.of(), null); }
    default void info(String message, Map<String, Object> fields) { log(PluginLogLevel.INFO, message, fields, null); }
    default void warn(String message) { log(PluginLogLevel.WARN, message, Map.of(), null); }
    default void warn(String message, Map<String, Object> fields) { log(PluginLogLevel.WARN, message, fields, null); }
    default void error(String message, Throwable error) { log(PluginLogLevel.ERROR, message, Map.of(), error); }
    /** Logs a plugin-local I18n key resolved from META-INF/kuudra-plugin/i18n/{locale}.json. */
    default void message(PluginLogLevel level, String key, Map<String, Object> arguments) {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>(arguments);
        fields.put(MESSAGE_KEY_FIELD, key);
        log(level, key, Map.copyOf(fields), null);
    }
}
