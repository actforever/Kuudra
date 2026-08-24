package io.github.actforever.kuudra.defaultplugin;

import io.github.actforever.kuudra.api.EventContext;
import io.github.actforever.kuudra.api.Ingress;
import io.github.actforever.kuudra.api.IngressDecision;
import io.github.actforever.kuudra.api.KuudraEvent;

/** Official unconditional RAW-to-SESSION boundary, grouped by configured groupKey or event type. */
public final class DefaultIngress implements Ingress {
    @Override
    public IngressDecision admit(KuudraEvent event, EventContext context) {
        String groupKey = context.configuration("groupKey", String.class,
                context.configuration("group-key", String.class, event.type()));
        return IngressDecision.accept(groupKey, event);
    }
}
