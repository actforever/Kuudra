package io.github.actforever.kuudra.api;

import java.util.List;

/** Filters or maps Event type/data while Runtime retains session ownership. */
@FunctionalInterface
public interface EventAdapter { List<KuudraEvent> adapt(KuudraEvent event, EventContext context); }
