package io.github.actforever.kuudra.api;

import java.util.concurrent.CompletionStage;

/** Asynchronous SESSION-domain business handler. */
@FunctionalInterface
public interface EventHandler extends Lifecycle {
    CompletionStage<Void> handle(KuudraEvent event, ActionContext context);
}
