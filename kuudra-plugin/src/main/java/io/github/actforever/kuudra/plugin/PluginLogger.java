package io.github.actforever.kuudra.plugin;

import java.util.Map;

/** Framework logger bound to one plugin identity and projected through SystemEvent. */
public interface PluginLogger {
    void log(PluginLogLevel level, String message, Map<String, Object> fields, Throwable error);

    default void trace(String message) { log(PluginLogLevel.TRACE, message, Map.of(), null); }
    default void debug(String message) { log(PluginLogLevel.DEBUG, message, Map.of(), null); }
    default void info(String message) { log(PluginLogLevel.INFO, message, Map.of(), null); }
    default void info(String message, Map<String, Object> fields) { log(PluginLogLevel.INFO, message, fields, null); }
    default void warn(String message) { log(PluginLogLevel.WARN, message, Map.of(), null); }
    default void warn(String message, Map<String, Object> fields) { log(PluginLogLevel.WARN, message, fields, null); }
    default void error(String message, Throwable error) { log(PluginLogLevel.ERROR, message, Map.of(), error); }
}
