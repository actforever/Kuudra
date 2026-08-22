package io.github.actforever.kuudra.api;

import java.util.List;

/** Stateful interpretation of unbound Events, including aggregation and gesture recognition. */
@FunctionalInterface
public interface EventProcessor { List<Event> process(Event event, EventContext context); }
