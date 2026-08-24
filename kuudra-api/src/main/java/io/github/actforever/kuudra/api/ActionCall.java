package io.github.actforever.kuudra.api;

import java.util.Map;

public record ActionCall(KuudraEvent event, ActionContext context, Map<String, Object> arguments) {
    public ActionCall { arguments = Map.copyOf(arguments); }
    public TypedValueMap argumentValues() { return TypedValueMap.of(arguments); }
    public <T> T argument(String key, Class<T> type) {
        return TypedValueMap.get(arguments, key, type);
    }
    public <T> T argument(String key, Class<T> type, T fallback) {
        return TypedValueMap.getOrDefault(arguments, key, type, fallback);
    }
}
