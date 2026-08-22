package io.github.actforever.kuudra.api;

import java.util.concurrent.CompletionStage;

/** The smallest asynchronous side-effect unit exposed by a plugin or a built-in component. */
@FunctionalInterface
public interface Action {
    CompletionStage<ActionResult> execute(ActionCall call);
}
