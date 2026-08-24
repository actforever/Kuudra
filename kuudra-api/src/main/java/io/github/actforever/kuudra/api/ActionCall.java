package io.github.actforever.kuudra.api;

import java.util.Map;

public record ActionCall(KuudraEvent event, ActionContext context, Map<String, Object> arguments) {
    public ActionCall { arguments = Map.copyOf(arguments); }
    public <T> T argument(String key, Class<T> type) {
        Object value = arguments.get(key);
        if (value == null) throw new IllegalArgumentException("Action argument is missing: " + key);
        return ContextCodecs.defaultCodec().decode(value, type);
    }
}
