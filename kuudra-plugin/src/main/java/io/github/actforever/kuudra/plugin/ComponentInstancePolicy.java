package io.github.actforever.kuudra.plugin;

/** Immutable instance and concurrency constraints discovered from a plugin component annotation. */
public record ComponentInstancePolicy(
        int maxInstances,
        ComponentLimitScope limitScope,
        String exclusivityDomain,
        boolean threadSafe
) {
    public static final ComponentInstancePolicy DEFAULT = new ComponentInstancePolicy(
            Integer.MAX_VALUE, ComponentLimitScope.APP, "", false);

    public ComponentInstancePolicy {
        if (maxInstances < 1) throw new IllegalArgumentException("maxInstances must be positive");
        if (limitScope == null) throw new IllegalArgumentException("limitScope must not be null");
        if (exclusivityDomain == null) throw new IllegalArgumentException("exclusivityDomain must not be null");
        if (!exclusivityDomain.isEmpty() && !exclusivityDomain.matches("[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("exclusivityDomain must be empty or authority/name");
        }
    }
}
