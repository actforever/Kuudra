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

/**
 * Stateful RAW-domain event interpretation such as sequence/window recognition before session admission.
 * Unlike an EventAdapter, an interpreter actively emits zero or more results and may retain its Runtime-owned
 * node scope after this method returns.
 */
@FunctionalInterface
public interface EventInterpreter extends Lifecycle {
    void interpret(KuudraEvent event, EventInterpreterContext context);
}
