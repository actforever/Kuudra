package io.github.actforever.kuudra.api.lifecycle;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Optional non-destructive lifecycle capability; stop remains a terminal resource release. */
public interface PausableLifecycle extends Lifecycle {
    default CompletionStage<Void> pause() { return CompletableFuture.completedFuture(null); }
    default CompletionStage<Void> resume() { return CompletableFuture.completedFuture(null); }
}
