package io.github.actforever.kuudra.plugin;

/** Immutable materialization and invocation constraints declared by a ResourceTemplate. */
public record ResourcePolicy(int maxInstances, ResourceLimitScope limitScope,
                             String exclusivityDomain, boolean allowParallel) {
    public static final ResourcePolicy DEFAULT = new ResourcePolicy(
            Integer.MAX_VALUE, ResourceLimitScope.APP, "", true);

    public ResourcePolicy {
        if (maxInstances < 1) throw new IllegalArgumentException("maxInstances must be positive");
        if (limitScope == null) throw new IllegalArgumentException("limitScope must not be null");
        if (exclusivityDomain == null) throw new IllegalArgumentException("exclusivityDomain must not be null");
        if (!exclusivityDomain.isEmpty()
                && !exclusivityDomain.matches("[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("exclusivityDomain must be empty or authority/name");
        }
    }
}
