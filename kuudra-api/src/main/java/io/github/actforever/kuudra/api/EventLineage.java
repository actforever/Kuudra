package io.github.actforever.kuudra.api;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Immutable provenance retained even when an Event leaves a Session. */
public record EventLineage(Set<UUID> parentEventIds, Set<UUID> parentSessionIds, int hops) {
    public EventLineage {
        parentEventIds = Set.copyOf(parentEventIds);
        parentSessionIds = Set.copyOf(parentSessionIds);
        if (hops < 0) throw new IllegalArgumentException("hops must not be negative");
    }
    public static EventLineage origin() { return new EventLineage(Set.of(), Set.of(), 0); }
    public EventLineage descendFrom(KuudraEvent event) {
        Set<UUID> events = new LinkedHashSet<>(parentEventIds); events.add(event.id());
        Set<UUID> sessions = new LinkedHashSet<>(parentSessionIds); sessions.addAll(event.lineage().parentSessionIds());
        return new EventLineage(events, sessions, Math.addExact(hops, 1));
    }

    public EventLineage descendFrom(KuudraEvent event, SessionReference session) {
        EventLineage descended = descendFrom(event);
        Set<UUID> sessions = new LinkedHashSet<>(descended.parentSessionIds());
        sessions.add(session.id());
        return new EventLineage(descended.parentEventIds(), sessions, descended.hops());
    }

}
