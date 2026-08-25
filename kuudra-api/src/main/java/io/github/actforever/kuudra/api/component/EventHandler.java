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

/** Asynchronous SESSION-domain business handler. */
@FunctionalInterface
public interface EventHandler extends Lifecycle {
    CompletionStage<Void> handle(KuudraEvent event, ActionContext context);
}
