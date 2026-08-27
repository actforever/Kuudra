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

import java.util.Map;
import java.util.UUID;

public record SessionSnapshot(UUID id, String flowId, long flowRevision, String ingressId, String groupKey,
                              Map<String, String> labels, SessionStatus status,
                              boolean cancellationRequested, int activeLeases) {
    public SessionSnapshot { labels = Map.copyOf(labels); }
}
