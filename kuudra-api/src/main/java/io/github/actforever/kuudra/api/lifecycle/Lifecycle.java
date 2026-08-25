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

import java.util.concurrent.CompletionStage;

/** A component with explicit, asynchronous ownership of external resources. */
public interface Lifecycle {
    default CompletionStage<Void> start() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    default CompletionStage<Void> stop() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
}
