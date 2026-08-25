package io.github.actforever.kuudra.api.component;

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

/** Handle returned when a source has been attached to a running KuudraRuntime. */
@FunctionalInterface
public interface SourceRegistration {
    /** Stops the source and removes it from the runtime. This operation is idempotent. */
    CompletionStage<Void> unregister();
}
