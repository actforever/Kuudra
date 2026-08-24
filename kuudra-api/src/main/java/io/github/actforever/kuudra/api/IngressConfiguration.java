package io.github.actforever.kuudra.api;

/** Runtime scheduling limits attached to one Ingress binding. */
public record IngressConfiguration(SessionSchedulingPolicy policy, SessionGroupScope groupScope,
                                   int maxParallelSessions, int queueCapacity) {
    public static final IngressConfiguration DEFAULT = new IngressConfiguration(
            SessionSchedulingPolicy.PARALLEL, SessionGroupScope.FLOW_BINDING, 64, 256);
    public IngressConfiguration {
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(groupScope, "groupScope");
        if (maxParallelSessions < 1) throw new IllegalArgumentException("maxParallelSessions must be positive");
        if (queueCapacity < 0) throw new IllegalArgumentException("queueCapacity must not be negative");
    }
}
