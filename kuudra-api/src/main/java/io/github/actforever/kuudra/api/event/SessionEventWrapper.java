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

import java.util.Objects;

/** Event admitted to exactly one Runtime-owned Session. */
public record SessionEventWrapper(KuudraEvent event, SessionReference session) implements KuudraEventWrapper {
    public SessionEventWrapper {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(session, "session");
    }
}
