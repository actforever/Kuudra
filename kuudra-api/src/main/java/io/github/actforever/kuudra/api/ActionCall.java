package io.github.actforever.kuudra.api;

import java.util.Map;

public record ActionCall(Event event, ActionContext context, Map<String, Object> arguments) {
    public ActionCall { arguments = Map.copyOf(arguments); }
}
