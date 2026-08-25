package io.github.actforever.kuudra.api.event;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

/** Closed execution-domain wrapper used by Runtime routing. */
public sealed interface KuudraEventWrapper permits RawEventWrapper, SessionEventWrapper {
    KuudraEvent event();
    default EventDomain domain() { return this instanceof RawEventWrapper ? EventDomain.RAW : EventDomain.SESSION; }
}
