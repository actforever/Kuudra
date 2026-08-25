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

import java.util.Map;

public sealed interface IngressDecision permits IngressDecision.Accepted, IngressDecision.Rejected {
    record Accepted(String groupKey, KuudraEvent event, Map<String, Object> initialSessionContext) implements IngressDecision {
        public Accepted {
            if (groupKey == null || groupKey.isBlank()) throw new IllegalArgumentException("groupKey must not be blank");
            java.util.Objects.requireNonNull(event, "event");
            initialSessionContext = Map.copyOf(initialSessionContext);
        }
        public Accepted(String groupKey, KuudraEvent event) { this(groupKey, event, Map.of()); }
    }
    record Rejected(String reason) implements IngressDecision {
        public Rejected { if (reason == null || reason.isBlank()) reason = "rejected"; }
    }
    static Accepted accept(String groupKey, KuudraEvent event) { return new Accepted(groupKey, event); }
    static Rejected reject(String reason) { return new Rejected(reason); }
}
