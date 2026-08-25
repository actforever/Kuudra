package io.github.actforever.kuudra.api.session;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

import java.util.UUID;

/** Immutable identity carried only by a SessionEventWrapper. */
public record SessionReference(UUID id, String flowId) {
    public SessionReference {
        if (id == null) throw new IllegalArgumentException("session id must not be null");
        if (flowId == null || flowId.isBlank()) throw new IllegalArgumentException("flowId must not be blank");
    }
}
