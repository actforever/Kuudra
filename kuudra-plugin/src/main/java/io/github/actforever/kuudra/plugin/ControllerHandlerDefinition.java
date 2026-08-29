package io.github.actforever.kuudra.plugin;

import io.github.actforever.kuudra.api.event.EventHandlerContext;
import io.github.actforever.kuudra.api.event.KuudraEvent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Validated method endpoint exposed by one Controller ResourceTemplate. */
public record ControllerHandlerDefinition(String name, Method method, String purpose,
                                          List<PluginConfigurationDocumentation> arguments,
                                          List<PluginEventDocumentation> emittedEvents) {
    public ControllerHandlerDefinition {
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("handler name must match [a-z0-9][a-z0-9-]*");
        }
        Objects.requireNonNull(method, "method");
        purpose = purpose == null ? "" : purpose;
        arguments = List.copyOf(arguments);
        emittedEvents = List.copyOf(emittedEvents);
    }

    @SuppressWarnings("unchecked")
    public CompletionStage<Void> invoke(Object controller, KuudraEvent event, EventHandlerContext context) {
        try {
            return (CompletionStage<Void>) method.invoke(controller, event, context);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return java.util.concurrent.CompletableFuture.failedFuture(cause);
        } catch (ReflectiveOperationException error) {
            return java.util.concurrent.CompletableFuture.failedFuture(error);
        }
    }
}
