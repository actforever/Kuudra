package io.github.actforever.kuudra.api.context;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

public interface CancellationToken {
    boolean isCancellationRequested();

    /** Cooperative pause signal for long-running or asynchronous component work. */
    default boolean isPauseRequested() { return false; }

    /** Completes when execution may continue; callers must not block Runtime worker threads. */
    default java.util.concurrent.CompletionStage<Void> awaitResumed() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
