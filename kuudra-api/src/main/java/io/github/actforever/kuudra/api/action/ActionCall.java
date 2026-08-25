package io.github.actforever.kuudra.api.action;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

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
