package io.github.actforever.kuudra.api;

import java.util.Map;

public record ActionCall(Signal signal, ActionContext context, Map<String, Object> arguments) {
    public ActionCall { arguments = Map.copyOf(arguments); }
}
