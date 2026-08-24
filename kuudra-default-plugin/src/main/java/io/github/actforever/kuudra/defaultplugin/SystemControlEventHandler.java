package io.github.actforever.kuudra.defaultplugin;

import io.github.actforever.kuudra.api.ActionContext;
import io.github.actforever.kuudra.api.EventHandler;
import io.github.actforever.kuudra.api.KuudraEvent;
import io.github.actforever.kuudra.plugin.KernelControlAction;
import io.github.actforever.kuudra.plugin.PluginComponentContext;
import io.github.actforever.kuudra.plugin.PluginComponentLifecycle;
import io.github.actforever.kuudra.plugin.PluginRuntimeServices;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Built-in bridge that turns a routed Event into an asynchronous kernel control request. */
public final class SystemControlEventHandler implements EventHandler, PluginComponentLifecycle {
    private PluginRuntimeServices runtime;
    @Override public CompletionStage<Void> initialize(PluginComponentContext context) {
        runtime = context.plugin().runtime();
        return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<Void> handle(KuudraEvent event, ActionContext context) {
        if (runtime == null) return CompletableFuture.failedFuture(new IllegalStateException("System control handler is not initialized"));
        String configured = context.configuration("action", String.class);
        KernelControlAction action = KernelControlAction.valueOf(configured.replace('-', '_').toUpperCase(Locale.ROOT));
        return runtime.control(action, context.sessionId());
    }
}
